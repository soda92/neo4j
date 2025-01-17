/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.runtime.scheduling;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.neo4j.bolt.runtime.BoltConnection;
import org.neo4j.bolt.runtime.Job;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.internal.LogService;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobScheduler;

import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.exception.ExceptionUtils.hasCause;

public class ExecutorBoltScheduler extends LifecycleAdapter implements BoltScheduler
{
    private final String connector;
    private final ExecutorFactory executorFactory;
    private final JobScheduler scheduler;
    private final Log log;
    private final ConcurrentHashMap<String,BoltConnection> activeConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<Boolean>> activeWorkItems = new ConcurrentHashMap<>();
    private final int corePoolSize;
    private final int maxPoolSize;
    private final Duration keepAlive;
    private final int queueSize;
    private final ExecutorService forkJoinPool;
    private final Duration shutdownWaitTime;

    private ExecutorService threadPool;

    private final BoltConnector.KeepAliveRequestType keepAliveRequestType;
    private final Duration keepAliveSchedulingInterval;
    private ScheduledExecutorService keepAliveService;

    public ExecutorBoltScheduler( String connector, ExecutorFactory executorFactory, JobScheduler scheduler,
                                  LogService logService, int corePoolSize, int maxPoolSize, Duration keepAlive, int queueSize,
                                  ExecutorService forkJoinPool, Duration shutdownWaitTime,
                                  BoltConnector.KeepAliveRequestType keepAliveRequestType, Duration keepAliveSchedulingInterval )
    {
        this.connector = connector;
        this.executorFactory = executorFactory;
        this.scheduler = scheduler;
        this.log = logService.getInternalLog( getClass() );
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAlive = keepAlive;
        this.queueSize = queueSize;
        this.forkJoinPool = forkJoinPool;
        this.shutdownWaitTime = shutdownWaitTime;
        this.keepAliveRequestType = keepAliveRequestType;
        this.keepAliveSchedulingInterval = keepAliveSchedulingInterval;
    }

    boolean isRegistered( BoltConnection connection )
    {
        return activeConnections.containsKey( connection.id() );
    }

    boolean isActive( BoltConnection connection )
    {
        return activeWorkItems.containsKey( connection.id() );
    }

    @Override
    public String connector()
    {
        return connector;
    }

    @Override
    public void init()
    {
        threadPool = executorFactory.create( corePoolSize, maxPoolSize, keepAlive, queueSize, true,
                new NameAppendingThreadFactory( connector, scheduler.threadFactory( Group.BOLT_WORKER ) ) );
        log.debug( "Initialized bolt thread pool." );
    }

    @Override
    public void start()
    {
        if ( keepAliveRequestType != BoltConnector.KeepAliveRequestType.STREAMING || keepAliveSchedulingInterval.isNegative() ||
             keepAliveSchedulingInterval.isZero() )
        {
            log.debug( "Bolt keep-alive service is disabled." );
        }
        else
        {
            keepAliveService = Executors.newSingleThreadScheduledExecutor();
            keepAliveService.scheduleAtFixedRate(
                    () ->
                    {
                        for ( var id : activeWorkItems.keySet() )
                        {
                            var connection = activeConnections.get( id );
                            connection.keepAlive();
                        }
                    },
                    keepAliveSchedulingInterval.toMillis(),
                    keepAliveSchedulingInterval.toMillis(),
                    MILLISECONDS
            );
            log.debug( "Initialized bolt keep-alive service." );
        }
    }

    @Override
    public void stop()
    {
        if ( keepAliveService != null )
        {
            log.debug( "Shutting down bolt keep-alive service." );
            keepAliveService.shutdown();
            log.debug( "Bolt keep-alive service shut down." );
        }
        // Close all idle connections
        log.debug( "Stopping idle connections." );
        activeConnections.values().stream().filter( BoltConnection::idle ).forEach( this::stopConnection );
        log.debug( "Idle connections stopped." );
    }

    @Override
    public void shutdown()
    {
        // Close all connections
        log.debug( "Stopping connections" );
        activeConnections.values().forEach( this::stopConnection );
        log.debug( "Connections stopped" );

        if ( threadPool != null )
        {
            log.debug( "Shutting down thread pool" );
            threadPool.shutdown();
            try
            {
                var terminatedCleanly = threadPool.awaitTermination( shutdownWaitTime.toMillis(), MILLISECONDS );
                if ( !terminatedCleanly )
                {
                    log.warn( "Waited %s for the thread pool to shutdown cleanly, but timed out waiting for existing work to finish cleanly",
                            shutdownWaitTime );
                }
                else
                {
                    log.debug( "Thread pool shut down" );
                }
            }
            catch ( InterruptedException ex )
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void created( BoltConnection connection )
    {
        BoltConnection previous = activeConnections.put( connection.id(), connection );
        // We do not expect the same (keyed) connection twice
        assert previous == null;
    }

    @Override
    public void closed( BoltConnection connection )
    {
        String id = connection.id();

        try
        {
            CompletableFuture<Boolean> currentFuture = activeWorkItems.remove( id );
            if ( currentFuture != null )
            {
                currentFuture.cancel( false );
            }
        }
        finally
        {
            activeConnections.remove( id );
        }
    }

    @Override
    public void enqueued( BoltConnection to, Job job )
    {
        handleSubmission( to );
    }

    @Override
    public void drained( BoltConnection from, Collection<Job> batch )
    {

    }

    private void handleSubmission( BoltConnection connection )
    {
        if ( keepAliveService != null )
        {
            // Only init timer if the service is enabled.
            connection.initKeepAliveTimer();
        }
        activeWorkItems.computeIfAbsent( connection.id(),
                key -> scheduleBatchOrHandleError( connection ).whenCompleteAsync( ( result, error ) -> handleCompletion( connection, result, error ),
                        forkJoinPool ) );
    }

    private CompletableFuture<Boolean> scheduleBatchOrHandleError( BoltConnection connection )
    {
        try
        {
            return supplyAsync( () -> executeBatch( connection ), threadPool );
        }
        catch ( RejectedExecutionException ex )
        {
            return failedFuture( ex );
        }
    }

    private static boolean executeBatch( BoltConnection connection )
    {
        Thread currentThread = Thread.currentThread();
        String originalName = currentThread.getName();
        String newName = String.format( "%s [%s] ", originalName, connection.remoteAddress() );

        currentThread.setName( newName );
        try
        {
            return connection.processNextBatch();
        }
        finally
        {
            currentThread.setName( originalName );
        }
    }

    private void handleCompletion( BoltConnection connection, Boolean shouldContinueScheduling, Throwable error )
    {
        try
        {
            if ( error != null && hasCause( error, RejectedExecutionException.class ) )
            {
                connection.handleSchedulingError( error );
                return;
            }
        }
        finally
        {
            // we need to ensure that the entry is removed only after any possible handleSchedulingError
            // call is completed. Otherwise, we can end up having different threads executing against
            // bolt state machine.
            activeWorkItems.remove( connection.id() );
        }

        if ( error != null )
        {
            log.error( String.format( "Unexpected error during job scheduling for session '%s'.", connection.id() ), error );
            stopConnection( connection );
        }
        else
        {
            if ( shouldContinueScheduling && connection.hasPendingJobs() )
            {
                handleSubmission( connection );
            }
        }
    }

    private void stopConnection( BoltConnection connection )
    {
        try
        {
            connection.stop();
        }
        catch ( Throwable t )
        {
            log.warn( String.format( "An unexpected error occurred while stopping BoltConnection [%s]", connection.id() ), t );
        }
    }

    private static class NameAppendingThreadFactory implements ThreadFactory
    {
        private final String nameToAppend;
        private final ThreadFactory factory;

        private NameAppendingThreadFactory( String nameToAppend, ThreadFactory factory )
        {
            this.nameToAppend = nameToAppend;
            this.factory = factory;
        }

        @Override
        public Thread newThread( Runnable r )
        {
            Thread newThread = factory.newThread( r );
            newThread.setName( String.format( "%s [%s]", newThread.getName(), nameToAppend ) );
            return newThread;
        }
    }
}
