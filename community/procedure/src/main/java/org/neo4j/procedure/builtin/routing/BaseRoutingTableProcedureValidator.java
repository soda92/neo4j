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
package org.neo4j.procedure.builtin.routing;

import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.database.DatabaseReference;
import org.neo4j.kernel.database.DatabaseReferenceRepository;

public abstract class BaseRoutingTableProcedureValidator implements RoutingTableProcedureValidator
{
    protected final DatabaseReferenceRepository databaseReferenceRepo;

    protected BaseRoutingTableProcedureValidator( DatabaseReferenceRepository databaseReferenceRepo )
    {
        this.databaseReferenceRepo = databaseReferenceRepo;
    }

    @Override
    public void assertDatabaseExists( DatabaseReference databaseReference ) throws ProcedureException
    {
        databaseReferenceRepo.getByName( databaseReference.alias() )
                             .orElseThrow( () -> RoutingTableProcedureHelpers.databaseNotFoundException( databaseReference.alias().name() ) );
    }
}
