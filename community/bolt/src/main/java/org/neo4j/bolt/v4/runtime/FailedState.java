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
package org.neo4j.bolt.v4.runtime;

import org.neo4j.bolt.messaging.RequestMessage;
import org.neo4j.bolt.runtime.BoltConnectionFatality;
import org.neo4j.bolt.runtime.statemachine.BoltStateMachineState;
import org.neo4j.bolt.runtime.statemachine.StateMachineContext;
import org.neo4j.bolt.v3.messaging.request.CommitMessage;
import org.neo4j.bolt.v3.messaging.request.InterruptSignal;
import org.neo4j.bolt.v3.messaging.request.RollbackMessage;
import org.neo4j.bolt.v3.messaging.request.RunMessage;
import org.neo4j.bolt.v4.messaging.DiscardMessage;
import org.neo4j.bolt.v4.messaging.PullMessage;
import org.neo4j.memory.HeapEstimator;

import static org.neo4j.util.Preconditions.checkState;

/**
 * The FAILED state occurs when a recoverable error is encountered.
 * This might be something like a Cypher SyntaxError or
 * ConstraintViolation. To exit the FAILED state, a RESET must be issued.
 * All stream will be IGNORED until this is done.
 */
public class FailedState implements BoltStateMachineState
{
    public static final long SHALLOW_SIZE = HeapEstimator.shallowSizeOfInstance( FailedState.class );

    private BoltStateMachineState interruptedState;

    @Override
    public BoltStateMachineState process( RequestMessage message, StateMachineContext context ) throws BoltConnectionFatality
    {
        assertInitialized();
        if ( shouldIgnore( message ) )
        {
            context.connectionState().markIgnored();
            return this;
        }
        if ( message instanceof InterruptSignal )
        {
            return interruptedState;
        }
        return null;
    }

    public void setInterruptedState( BoltStateMachineState interruptedState )
    {
        this.interruptedState = interruptedState;
    }

    protected void assertInitialized()
    {
        checkState( interruptedState != null, "Interrupted state not set" );
    }

    @Override
    public String name()
    {
        return "FAILED";
    }

    protected boolean shouldIgnore( RequestMessage message )
    {
        return message instanceof RunMessage || message instanceof PullMessage || message instanceof DiscardMessage
                || message instanceof CommitMessage || message instanceof RollbackMessage;
    }
}
