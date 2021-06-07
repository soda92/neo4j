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
package org.neo4j.kernel.impl.storemigration.legacy;

import java.util.Collection;
import java.util.Iterator;
import java.util.function.Predicate;

import org.neo4j.function.Predicates;
import org.neo4j.internal.helpers.collection.PrefetchingIterator;
import org.neo4j.internal.kernel.api.exceptions.schema.MalformedSchemaRuleException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.SchemaRule;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.store.InvalidRecordException;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.storemigration.SchemaStorage;
import org.neo4j.storageengine.api.cursor.StoreCursors;

import static org.neo4j.storageengine.api.cursor.CursorTypes.SCHEMA_CURSOR;

/**
 * A stripped down 3.5.x version of SchemaStorage, used for schema store migration.
 */
public class SchemaStorage35 implements SchemaStorage
{
    private final RecordStore<DynamicRecord> schemaStore;

    public SchemaStorage35( RecordStore<DynamicRecord> schemaStore )
    {
        this.schemaStore = schemaStore;
    }

    @Override
    public Iterable<SchemaRule> getAll( StoreCursors storeCursors )
    {
        return () -> loadAllSchemaRules( (SchemaStore35StoreCursors) storeCursors );
    }

    public Iterator<IndexDescriptor> indexesGetAll( SchemaStore35StoreCursors storeCursors )
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), IndexDescriptor.class, storeCursors );
    }

    public IndexDescriptor indexGetForName( String indexName, SchemaStore35StoreCursors storeCursors )
    {
        Iterator<IndexDescriptor> itr = indexesGetAll( storeCursors );
        while ( itr.hasNext() )
        {
            IndexDescriptor sid = itr.next();
            if ( sid.getName().equals( indexName ) )
            {
                return sid;
            }
        }
        return null;
    }

    private Iterator<SchemaRule> loadAllSchemaRules( SchemaStore35StoreCursors storeCursors )
    {
        return loadAllSchemaRules( Predicates.alwaysTrue(), SchemaRule.class, storeCursors );
    }

    /**
     * Scans the schema store and loads all {@link SchemaRule rules} in it. This method is written with the assumption
     * that there's no id reuse on schema records.
     *
     * @param predicate filter when loading.
     * @param returnType type of {@link SchemaRule} to load.
     * @return {@link Iterator} of the loaded schema rules, lazily loaded when advancing the iterator.
     */
    private <ReturnType extends SchemaRule> Iterator<ReturnType> loadAllSchemaRules( final Predicate<ReturnType> predicate, final Class<ReturnType> returnType,
            SchemaStore35StoreCursors storeCursors )
    {
        return new PrefetchingIterator<>()
        {
            private final long highestId = schemaStore.getHighestPossibleIdInUse( CursorContext.NULL );
            private long currentId = 1; /*record 0 contains the block size*/
            private final byte[] scratchData = newRecordBuffer();
            private final DynamicRecord record = schemaStore.newRecord();

            @Override
            protected ReturnType fetchNextOrNull()
            {
                while ( currentId <= highestId )
                {
                    long id = currentId++;
                    schemaStore.getRecordByCursor( id, record, RecordLoad.LENIENT_CHECK, storeCursors.pageCursor( SCHEMA_CURSOR ) );
                    if ( !record.inUse() )
                    {
                        continue;
                    }
                    schemaStore.getRecordByCursor( id, record, RecordLoad.NORMAL, storeCursors.pageCursor( SCHEMA_CURSOR ) );
                    if ( record.isStartRecord() )
                    {
                        // It may be that concurrently to our reading there's a transaction dropping the schema rule
                        // that we're reading and that rule may have spanned multiple dynamic records.
                        try
                        {
                            Collection<DynamicRecord> records;
                            try
                            {
                                records = schemaStore.getRecords( id, RecordLoad.NORMAL, false, storeCursors.pageCursor( SCHEMA_CURSOR ) );
                            }
                            catch ( InvalidRecordException e )
                            {
                                // This may have been due to a concurrent drop of this rule.
                                continue;
                            }

                            SchemaRule schemaRule = SchemaStore35.readSchemaRule( id, records, scratchData );
                            if ( returnType.isInstance( schemaRule ) )
                            {
                                ReturnType returnRule = returnType.cast( schemaRule );
                                if ( predicate.test( returnRule ) )
                                {
                                    return returnRule;
                                }
                            }
                        }
                        catch ( MalformedSchemaRuleException e )
                        {
                            throw new RuntimeException( e );
                        }
                    }
                }
                return null;
            }
        };
    }

    private byte[] newRecordBuffer()
    {
        return new byte[schemaStore.getRecordSize() * 4];
    }
}
