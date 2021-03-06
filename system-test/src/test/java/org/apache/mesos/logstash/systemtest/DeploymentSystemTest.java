package org.apache.mesos.logstash.systemtest;

import com.containersol.minimesos.cluster.MesosCluster;
import com.containersol.minimesos.mesos.ClusterArchitecture;
import com.containersol.minimesos.mesos.DockerClientFactory;
import com.containersol.minimesos.state.State;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Link;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.*;

/**
 * Tests whether the framework is deployed correctly
 */
@SuppressWarnings("Duplicates")
public class DeploymentSystemTest {

    private static DockerClient dockerClient = DockerClientFactory.build();

    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentSystemTest.class);

    private MesosCluster cluster = new MesosCluster(new ClusterArchitecture.Builder()
            .withZooKeeper()
            .withMaster(zooKeeper -> new LogstashMesosMaster(dockerClient, zooKeeper))
            .withAgent(zooKeeper -> new LogstashMesosSlave(dockerClient, zooKeeper))
            .build());

    Optional<LogstashSchedulerContainer> scheduler = Optional.empty();
    protected File tmpRoot = new File(".tmp");
    protected File tmpDir;

    @Before
    public void before() throws Exception {
        tmpDir = new File(tmpRoot, String.valueOf(System.currentTimeMillis()));
        FileUtils.forceMkdir(tmpDir);
        tmpRoot.deleteOnExit();
        cluster.start();
    }

    @After
    public void after() throws Exception {
        scheduler.ifPresent(scheduler -> dockerClient.stopContainerCmd(scheduler.getContainerId()).withTimeout(30).exec());

        await().atMost(30, SECONDS).pollInterval(1, SECONDS).until(() -> {
            JSONArray frameworks = cluster.getClusterStateInfo().getJSONArray("frameworks");
            assertEquals(0, frameworks.length());
        });
        cluster.stop();

    }

    private void deployScheduler(String mesosRole, String elasticsearchHost, boolean useDocker, File logstashConfig, boolean enableSyslog) {
        String zookeeperIpAddress = cluster.getZkContainer().getIpAddress();
        String mesosMasterIpAddress = cluster.getMasterContainer().getIpAddress();
        this.scheduler = Optional.of(new LogstashSchedulerContainer(dockerClient, mesosMasterIpAddress, zookeeperIpAddress, mesosRole, elasticsearchHost));
        this.scheduler.get().setDocker(useDocker);
        if (enableSyslog) {
            this.scheduler.get().enableSyslog();
        }
        if (logstashConfig != null) {
            this.scheduler.get().setLogstashConfig(logstashConfig);
        }
        cluster.addAndStartContainer(this.scheduler.get());

        waitForFramework();
    }

    private void waitForFramework() {
        await().atMost(15, SECONDS).pollInterval(1, SECONDS).until(() -> {
            JSONArray frameworks = getFrameworks();
            assertEquals("only one framework is expected", 1, frameworks.length());
            JSONObject framework = frameworks.getJSONObject(0);
            assertTrue("framework is expected to have running tasks", framework.has("tasks"));
        });
        await().atMost(10, SECONDS).pollInterval(1, SECONDS).until(() -> {
            JSONArray tasks = getFrameworks().getJSONObject(0).getJSONArray("tasks");
            assertEquals("only one task of the framework is expected", 1, tasks.length());
            assertTrue("the task is expected to have a name", tasks.getJSONObject(0).has("name"));
            assertEquals("the task should have the predefined name", "logstash.task", tasks.getJSONObject(0).getString("name"));
        });
        await().atMost(60, SECONDS).pollInterval(1, SECONDS).until(() -> {
            assertEquals("task expected to be running", "TASK_RUNNING", getFrameworks().getJSONObject(0).getJSONArray("tasks").getJSONObject(0).getString("state"));
        });
    }

    protected JSONArray getFrameworks() {
        return cluster.getClusterStateInfo().getJSONArray("frameworks");
    }

    @Test
    public void testDeploymentDocker() throws JsonParseException, UnirestException, JsonMappingException {
        deployScheduler(null, null, true, null, false);
    }

    @Test
    public void testDeploymentJar() throws JsonParseException, UnirestException, JsonMappingException {
        deployScheduler(null, null, false, null, false);
    }

    @Test
    public void testDeploymentExternalConfiguration() throws Exception {
        final ElasticsearchContainer elasticsearchInstance = new ElasticsearchContainer(dockerClient);
        cluster.addAndStartContainer(elasticsearchInstance);

        final File logstashConfig = new File(tmpDir, "logstash.config");
        FileUtils.writeStringToFile(logstashConfig, "input { generator {} } output { elasticsearch { hosts => \"" + elasticsearchInstance.getIpAddress() + ":9200" + "\" } }");

        Client elasticsearchClient = elasticsearchInstance.createClient();

        deployScheduler(null, null, false, logstashConfig, false);

        SECONDS.sleep(2);

        await().atMost(20, SECONDS).pollDelay(1, SECONDS).until(() -> {
            final SearchHits hits = elasticsearchClient.prepareSearch("logstash-*").setQuery(QueryBuilders.simpleQueryStringQuery("Hello*")).addField("message").addField("mesos_agent_id").execute().actionGet().getHits();
            assertNotEquals(0, hits.totalHits());
            Map<String, SearchHitField> fields = hits.getAt(0).fields();

            String esMessage = fields.get("message").getValue();
            assertEquals("Hello world!", esMessage.trim());
        });

    }

    @Test
    public void willForwardDataToElasticsearchInDockerMode() throws Exception {
        final ElasticsearchContainer elasticsearchInstance = new ElasticsearchContainer(dockerClient);
        cluster.addAndStartContainer(elasticsearchInstance);

        Client elasticsearchClient = elasticsearchInstance.createClient();

        deployScheduler("logstash", elasticsearchInstance.getIpAddress() + ":9200", true, null, true);

        final String sysLogPort = "514";
        final String randomLogLine = "Hello " + RandomStringUtils.randomAlphanumeric(32);

        dockerClient.pullImageCmd("ubuntu:15.10").exec(new PullImageResultCallback()).awaitSuccess();
        final String logstashSlave = dockerClient.listContainersCmd().withSince(cluster.getAgents().get(0).getContainerId()).exec().stream().filter(container -> container.getImage().endsWith("/logstash-executor:latest")).findFirst().map(Container::getId).orElseThrow(() -> new AssertionError("Unable to find logstash container"));

        assertTrue("logstash slave is expected to be running", dockerClient.inspectContainerCmd(logstashSlave).exec().getState().isRunning());

        final CreateContainerResponse loggerContainer = dockerClient.createContainerCmd("ubuntu:15.10").withLinks(new Link(logstashSlave, "logstash")).withCmd("logger", "--server=logstash", "--port=" + sysLogPort, "--udp", "--rfc3164", randomLogLine).exec();
        dockerClient.startContainerCmd(loggerContainer.getId()).exec();

        await().atMost(5, SECONDS).pollDelay(1, SECONDS).until(() -> {
            final String finishedAt = dockerClient.inspectContainerCmd(loggerContainer.getId()).exec().getState().getFinishedAt();
            assertNotEquals("", finishedAt.trim());
            assertNotEquals("0001-01-01T00:00:00Z", finishedAt);
        });

        final int exitCode = dockerClient.inspectContainerCmd(loggerContainer.getId()).exec().getState().getExitCode();
        dockerClient.removeContainerCmd(loggerContainer.getId()).exec();
        assertEquals(0, exitCode);

        await().atMost(10, SECONDS).pollDelay(1, SECONDS).until(() -> {
            final SearchHits hits = elasticsearchClient.prepareSearch("logstash-*").setQuery(QueryBuilders.simpleQueryStringQuery("Hello")).addField("message").addField("mesos_agent_id").execute().actionGet().getHits();
            assertEquals(1, hits.totalHits());
            Map<String, SearchHitField> fields = hits.getAt(0).fields();

            String esMessage = fields.get("message").getValue();
            assertEquals(randomLogLine, esMessage.trim());

            String esMesosSlaveId = fields.get("mesos_agent_id").getValue();

            String trueSlaveId;
            try {
                trueSlaveId = cluster.getClusterStateInfo().getJSONArray("slaves").getJSONObject(0).getString("id");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertEquals(trueSlaveId, esMesosSlaveId.trim());
        });
    }

    @Test
    public void willForwardDataToElasticsearchInJarMode() throws Exception {
        final ElasticsearchContainer elasticsearchInstance = new ElasticsearchContainer(dockerClient);
        cluster.addAndStartContainer(elasticsearchInstance);

        Client elasticsearchClient = elasticsearchInstance.createClient();

        deployScheduler("logstash", elasticsearchInstance.getIpAddress() + ":9200", false, null, true);

        final String sysLogPort = "514";
        final String randomLogLine = "Hello " + RandomStringUtils.randomAlphanumeric(32);

        dockerClient.pullImageCmd("ubuntu:15.10").exec(new PullImageResultCallback()).awaitSuccess();
        final String logstashSlave = cluster.getAgents().get(0).getContainerId();

        assertTrue(dockerClient.inspectContainerCmd(logstashSlave).exec().getState().isRunning());

        final CreateContainerResponse loggerContainer = dockerClient.createContainerCmd("ubuntu:15.10").withLinks(new Link(logstashSlave, "logstash")).withCmd("logger", "--server=logstash", "--port=" + sysLogPort, "--udp", "--rfc3164", randomLogLine).exec();
        dockerClient.startContainerCmd(loggerContainer.getId()).exec();

        await().atMost(5, SECONDS).pollDelay(1, SECONDS).until(() -> {
            final String finishedAt = dockerClient.inspectContainerCmd(loggerContainer.getId()).exec().getState().getFinishedAt();
            assertNotEquals("", finishedAt.trim());
            assertNotEquals("0001-01-01T00:00:00Z", finishedAt);
        });


        final int exitCode = dockerClient.inspectContainerCmd(loggerContainer.getId()).exec().getState().getExitCode();
        dockerClient.removeContainerCmd(loggerContainer.getId()).exec();
        assertEquals(0, exitCode);

        await().atMost(10, SECONDS).pollDelay(1, SECONDS).until(() -> {
            final SearchHits hits = elasticsearchClient.prepareSearch("logstash-*").setQuery(QueryBuilders.simpleQueryStringQuery("Hello")).addField("message").addField("mesos_agent_id").execute().actionGet().getHits();
            assertEquals(1, hits.totalHits());
            Map<String, SearchHitField> fields = hits.getAt(0).fields();

            String esMessage = fields.get("message").getValue();
            assertEquals(randomLogLine, esMessage.trim());

            String esMesosSlaveId = fields.get("mesos_agent_id").getValue();

            String trueSlaveId;
            try {
                trueSlaveId = cluster.getClusterStateInfo().getJSONArray("slaves").getJSONObject(0).getString("id");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertEquals(trueSlaveId, esMesosSlaveId.trim());
        });
    }

    @Test
    public void willAddExecutorOnNewNodes() throws JsonParseException, UnirestException, JsonMappingException {
        deployScheduler(null, null, true, null, false);

        IntStream.range(0, 2).forEach(value -> cluster.addAndStartContainer(new LogstashMesosSlave(dockerClient, cluster.getZkContainer())));

        await().atMost(1, TimeUnit.MINUTES).pollInterval(1, SECONDS).until(
                () -> State.fromJSON(cluster.getClusterStateInfo().toString()).getFramework("logstash").getTasks().stream().filter(task -> task.getState().equals("TASK_RUNNING")).count() == 3
        );

        // TODO use com.containersol.minimesos.state.Task when it exposes the slave_id property https://github.com/ContainerSolutions/minimesos/issues/168
        JSONArray tasks = cluster.getClusterStateInfo().getJSONArray("frameworks").getJSONObject(0).getJSONArray("tasks");
        Set<String> slaveIds = new TreeSet<>();
        for (int i = 0; i < tasks.length(); i++) {
            slaveIds.add(tasks.getJSONObject(i).getString("slave_id"));
        }
        assertEquals(3, slaveIds.size());
    }

    @Test
    public void willStartNewExecutorIfOldExecutorFails() throws Exception {
        deployScheduler("logstash", null, true, null, false);

        Function<String, Stream<Container>> getLogstashExecutorsSince = containerId -> dockerClient
                .listContainersCmd()
                .withSince(containerId)
                .exec()
                .stream()
                .filter(container -> container.getImage().endsWith("/logstash-executor:latest"));

        await().atMost(1, TimeUnit.MINUTES).pollDelay(1, SECONDS).until(() -> {
            long count = getLogstashExecutorsSince.apply(cluster.getAgents().get(0).getContainerId()).count();
            LOGGER.info("There are " + count + " executors since " + cluster.getAgents().get(0).getContainerId());
            assertEquals(1, count);
        });

        final String slaveToKillContainerId = getLogstashExecutorsSince.apply(cluster.getAgents().get(0).getContainerId()).findFirst().map(Container::getId).orElseThrow(() -> new RuntimeException("Unable to find logstash container"));

        dockerClient.killContainerCmd(slaveToKillContainerId).exec();

        await().atMost(1, TimeUnit.MINUTES).pollDelay(1, SECONDS).until(() -> {
            assertEquals(1, getLogstashExecutorsSince.apply(slaveToKillContainerId).count());
        });
    }
}
