/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.stresstests;

import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import org.neo4j.backup.OnlineBackupSettings;
import org.neo4j.coreedge.core.CoreEdgeClusterSettings;
import org.neo4j.coreedge.discovery.Cluster;
import org.neo4j.coreedge.discovery.HazelcastDiscoveryServiceFactory;
import org.neo4j.helpers.AdvertisedSocketAddress;
import org.neo4j.helpers.SocketAddress;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_0;

import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.lang.System.getProperty;
import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertTrue;
import static org.neo4j.StressTestingHelper.ensureExistsAndEmpty;
import static org.neo4j.StressTestingHelper.fromEnv;
import static org.neo4j.function.Suppliers.untilTimeExpired;
import static org.neo4j.kernel.configuration.Settings.TRUE;

public class BackupStoreCopyInteractionStressTesting
{
    private static final String DEFAULT_NUMBER_OF_CORES = "3";
    private static final String DEFAULT_NUMBER_OF_EDGES = "1";
    private static final String DEFAULT_DURATION_IN_MINUTES = "30";
    private static final String DEFAULT_WORKING_DIR = new File( getProperty( "java.io.tmpdir" ) ).getPath();
    private static final String DEFAULT_BASE_CORE_BACKUP_PORT = "8000";
    private static final String DEFAULT_BASE_EDGE_BACKUP_PORT = "9000";

    @Test
    public void shouldBehaveCorrectlyUnderStress() throws Exception
    {
        int numberOfCores =
                parseInt( fromEnv( "BACKUP_STORE_COPY_INTERACTION_STRESS_NUMBER_OF_CORES", DEFAULT_NUMBER_OF_CORES ) );
        int numberOfEdges =
                parseInt( fromEnv( "BACKUP_STORE_COPY_INTERACTION_STRESS_NUMBER_OF_EDGES", DEFAULT_NUMBER_OF_EDGES ) );
        long durationInMinutes =
                parseLong( fromEnv( "BACKUP_STORE_COPY_INTERACTION_STRESS_DURATION", DEFAULT_DURATION_IN_MINUTES ) );
        String workingDirectory =
                fromEnv( "BACKUP_STORE_COPY_INTERACTION_STRESS_WORKING_DIRECTORY", DEFAULT_WORKING_DIR );
        int baseCoreBackupPort = parseInt( fromEnv( "BACKUP_STORE_COPY_INTERACTION_STRESS_BASE_CORE_BACKUP_PORT",
                DEFAULT_BASE_CORE_BACKUP_PORT ) );
        int baseEdgeBackupPort = parseInt( fromEnv( "BACKUP_STORE_COPY_INTERACTION_STRESS_BASE_EDGE_BACKUP_PORT",
                DEFAULT_BASE_EDGE_BACKUP_PORT ) );

        File clusterDirectory = ensureExistsAndEmpty( new File( workingDirectory, "cluster" ) );
        File backupDirectory = ensureExistsAndEmpty( new File( workingDirectory, "backups" ) );

        BiFunction<Boolean,Integer,SocketAddress> backupAddress = ( isCore, id ) ->
                new AdvertisedSocketAddress( "localhost", (isCore ? baseCoreBackupPort : baseEdgeBackupPort) + id );

        Map<String,String> coreParams = new HashMap<>();
        coreParams.put( CoreEdgeClusterSettings.raft_log_rotation_size.name(), "1K" );
        coreParams.put( CoreEdgeClusterSettings.raft_log_pruning_frequency.name(), "1s" );
        coreParams.put( CoreEdgeClusterSettings.raft_log_pruning_strategy.name(), "keep_none" );

        Map<String,IntFunction<String>> paramsPerCoreInstance = configureBackup( (id) -> backupAddress.apply( true, id ) );
        Map<String,IntFunction<String>> paramsPerEdgeInstance = configureBackup( (id) -> backupAddress.apply( false, id ) );

        HazelcastDiscoveryServiceFactory discoveryServiceFactory = new HazelcastDiscoveryServiceFactory();
        Cluster cluster =
                new Cluster( clusterDirectory, numberOfCores, numberOfEdges, discoveryServiceFactory, coreParams,
                        paramsPerCoreInstance, emptyMap(), paramsPerEdgeInstance, StandardV3_0.NAME );

        AtomicBoolean stopTheWorld = new AtomicBoolean();
        BooleanSupplier keepGoing =
                () -> !stopTheWorld.get() && untilTimeExpired( durationInMinutes, TimeUnit.MINUTES ).getAsBoolean();
        Runnable onFailure = () -> stopTheWorld.set( true );

        ExecutorService service = Executors.newFixedThreadPool( 3 );
        try
        {
            cluster.start();
            Future<Boolean> workload = service.submit( new Workload( keepGoing, onFailure, cluster ) );
            Future<Boolean> startStopWorker = service.submit( new StartStopLoad( keepGoing, onFailure, cluster ) );
            Future<Boolean> backupWorker =
                    service.submit( new BackupLoad( keepGoing, onFailure, cluster, backupDirectory, backupAddress ) );

            assertTrue( workload.get() );
            assertTrue( startStopWorker.get() );
            assertTrue( backupWorker.get() );
        }
        finally
        {
            cluster.shutdown();
        }

        // let's cleanup disk space when everything went well
        FileUtils.deleteRecursively( clusterDirectory );
        FileUtils.deleteRecursively( backupDirectory );
    }

    private static Map<String,IntFunction<String>> configureBackup( IntFunction<SocketAddress> address )
    {
        Map<String,IntFunction<String>> settings = new HashMap<>();
        settings.put( OnlineBackupSettings.online_backup_enabled.name(), id -> TRUE );
        settings.put( OnlineBackupSettings.online_backup_server.name(), id -> address.apply( id ).toString() );
        return settings;
    }
}
