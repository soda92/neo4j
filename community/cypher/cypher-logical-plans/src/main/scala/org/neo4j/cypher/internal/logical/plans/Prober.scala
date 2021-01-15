/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.cypher.internal.logical.plans

import org.neo4j.cypher.internal.logical.plans.Prober.Probe
import org.neo4j.cypher.internal.util.attribution.IdGen

/**
 * Install a probe to observe data flowing through the query
 *
 * NOTE: This plan is only for testing
 */
case class Prober(source: LogicalPlan, probe: Probe)(implicit idGen: IdGen) extends LogicalPlan(idGen)  {

  val lhs: Option[LogicalPlan] = Some(source)
  def rhs: Option[LogicalPlan] = None

  val availableSymbols: Set[String] = source.availableSymbols
}

object Prober {
  trait Probe {
    /**
     * Called on each row that passes through this operator.
     *
     * NOTE: The row object is transient and any data that needs to be stored
     * should be copied before the call returns.
     *
     * @param row a CypherRow representation
     */
    def onRow(row: AnyRef): Unit
  }

  object NoopProbe extends Probe {
    override def onRow(row: AnyRef): Unit = {}
  }
}
