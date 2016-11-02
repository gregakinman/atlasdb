/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.cassandra.multinode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.jayway.awaitility.Awaitility;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfigManager;
import com.palantir.atlasdb.containers.Containers;
import com.palantir.atlasdb.containers.ThreeNodeCassandraCluster;
import com.palantir.atlasdb.keyvalue.cassandra.CassandraClientPool;
import com.palantir.atlasdb.keyvalue.cassandra.CassandraKeyValueService;
import com.palantir.docker.compose.connection.Container;

public class DegradedClusterInitializationTest {
    private static final String CASSANDRA_NODE_TO_KILL = ThreeNodeCassandraCluster.FIRST_CASSANDRA_CONTAINER_NAME;

    @ClassRule
    public static final Containers CONTAINERS = new Containers(DegradedClusterInitializationTest.class)
            .with(new ThreeNodeCassandraCluster());

    @BeforeClass
    public static void createKvsAndDegradeCluster() throws IOException, InterruptedException {
        createCassandraKeyValueService();
        degradeCassandraCluster();
    }

    @AfterClass
    public static void bringClusterBack() throws IOException, InterruptedException {
        startDeadCassandraNode();
        CONTAINERS.waitForContainersToStart();
    }

    @Test(expected = IllegalStateException.class)
    public void creatingCassandraKvsShouldFail()  {
        createCassandraKeyValueService();
    }

    private static void createCassandraKeyValueService() {
        CassandraKeyValueService.create(
                CassandraKeyValueServiceConfigManager.createSimpleManager(ThreeNodeCassandraCluster.KVS_CONFIG),
                ThreeNodeCassandraCluster.LEADER_CONFIG);
    }

    private static void degradeCassandraCluster() throws IOException, InterruptedException {
        killFirstCassandraNode();

        // startup checks aren't guaranteed to pass immediately after killing the node, so we wait until
        // they do. unclear if this is an AtlasDB product problem
        waitUntilStartupChecksPass();
    }

    private static void killFirstCassandraNode() throws IOException, InterruptedException {
        Container container = CONTAINERS.getContainer(CASSANDRA_NODE_TO_KILL);
        container.kill();
    }

    private static void startDeadCassandraNode() throws IOException, InterruptedException {
        Container container = CONTAINERS.getContainer(CASSANDRA_NODE_TO_KILL);
        container.start();
    }

    private static void waitUntilStartupChecksPass() {
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .until(DegradedClusterInitializationTest::startupChecksPass);
    }

    private static boolean startupChecksPass() {
        CassandraKeyValueServiceConfigManager manager = CassandraKeyValueServiceConfigManager.createSimpleManager(
                ThreeNodeCassandraCluster.KVS_CONFIG);
        CassandraClientPool pool = new CassandraClientPool(manager.getConfig());
        try {
            pool.runOneTimeStartupChecks();
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }
}
