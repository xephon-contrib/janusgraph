// Copyright 2017 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.graphdb.olap.computer;

import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphVertex;
import org.janusgraph.diskstorage.EntryList;
import org.janusgraph.diskstorage.configuration.Configuration;
import org.janusgraph.diskstorage.keycolumnvalue.SliceQuery;
import org.janusgraph.diskstorage.keycolumnvalue.scan.ScanMetrics;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.database.idhandling.IDHandler;
import org.janusgraph.graphdb.idmanagement.IDManager;
import org.janusgraph.graphdb.internal.RelationCategory;
import org.janusgraph.graphdb.olap.QueryContainer;
import org.janusgraph.graphdb.olap.VertexJobConverter;
import org.janusgraph.graphdb.olap.VertexScanJob;
import org.janusgraph.graphdb.tinkerpop.optimize.JanusGraphVertexStep;
import org.janusgraph.graphdb.vertices.PreloadedVertex;
import org.apache.tinkerpop.gremlin.process.computer.MessageCombiner;
import org.apache.tinkerpop.gremlin.process.computer.MessageScope;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class VertexProgramScanJob<M> implements VertexScanJob {

    private static final Logger log =
            LoggerFactory.getLogger(VertexProgramScanJob.class);

    private final IDManager idManager;
    private final FulgoraMemory memory;
    private final FulgoraVertexMemory<M> vertexMemory;
    private final VertexProgram<M> vertexProgram;

    private final MessageCombiner<M> combiner;

    private VertexProgramScanJob(IDManager idManager, FulgoraMemory memory,
                                FulgoraVertexMemory vertexMemory, VertexProgram<M> vertexProgram) {
        this.idManager = idManager;
        this.memory = memory;
        this.vertexMemory = vertexMemory;
        this.vertexProgram = vertexProgram;
        this.combiner = FulgoraUtil.getMessageCombiner(vertexProgram);
    }

    @Override
    public VertexProgramScanJob<M> clone() {
        return new VertexProgramScanJob<>(this.idManager, this.memory, this.vertexMemory, this.vertexProgram
                .clone());
    }

    @Override
    public void workerIterationStart(JanusGraph graph, Configuration config, ScanMetrics metrics) {
        vertexProgram.workerIterationStart(memory.asImmutable());
    }

    @Override
    public void workerIterationEnd(ScanMetrics metrics) {
        vertexProgram.workerIterationEnd(memory.asImmutable());
    }

    @Override
    public void process(JanusGraphVertex vertex, ScanMetrics metrics) {
        PreloadedVertex v = (PreloadedVertex)vertex;
        long vertexId = v.longId();
        VertexMemoryHandler<M> vh = new VertexMemoryHandler(vertexMemory,v);
        v.setAccessCheck(PreloadedVertex.OPENSTAR_CHECK);
        if (idManager.isPartitionedVertex(vertexId)) {
            if (idManager.isCanonicalVertexId(vertexId)) {
                EntryList results = v.getFromCache(SYSTEM_PROPS_QUERY);
                if (results == null) results = EntryList.EMPTY_LIST;
                vertexMemory.setLoadedProperties(vertexId,results);
            }
            for (MessageScope scope : vertexMemory.getPreviousScopes()) {
                if (scope instanceof MessageScope.Local) {
                    M combinedMsg = null;
                    for (Iterator<M> msgIter = vh.receiveMessages(scope).iterator(); msgIter.hasNext(); ) {
                        M msg = msgIter.next();
                        if (combinedMsg==null) combinedMsg=msg;
                        else combinedMsg = combiner.combine(combinedMsg,msg);
                    }
                    if (combinedMsg!=null) vertexMemory.aggregateMessage(vertexId,combinedMsg,scope);
                }
            }
        } else {
            v.setPropertyMixing(vh);
            vertexProgram.execute(v, vh, memory);
        }
    }

    @Override
    public void getQueries(QueryContainer queries) {
        if (vertexProgram instanceof TraversalVertexProgram) {
            //TraversalVertexProgram currently makes the assumption that the entire star-graph around a vertex
            //is available (in-memory). Hence, this special treatment here.
            //TODO: After TraversalVertexProgram is adjusted, remove this
            queries.addQuery().direction(Direction.BOTH).edges();
            return;
        }

        for (MessageScope scope : vertexMemory.getPreviousScopes()) {
            if (scope instanceof MessageScope.Global) {
                queries.addQuery().direction(Direction.BOTH).edges();
            } else {
                assert scope instanceof MessageScope.Local;
                JanusGraphVertexStep<Vertex> startStep = FulgoraUtil.getReverseJanusGraphVertexStep((MessageScope.Local) scope,queries.getTransaction());
                QueryContainer.QueryBuilder qb = queries.addQuery();
                startStep.makeQuery(qb);
                qb.edges();
            }
        }
    }


    public static<M> Executor getVertexProgramScanJob(StandardJanusGraph graph, FulgoraMemory memory,
                                                  FulgoraVertexMemory vertexMemory, VertexProgram<M> vertexProgram) {
        VertexProgramScanJob<M> job = new VertexProgramScanJob<M>(graph.getIDManager(),memory,vertexMemory,vertexProgram);
        return new Executor(graph,job);
    }

    //Query for all system properties+edges and normal properties
    static final SliceQuery SYSTEM_PROPS_QUERY = new SliceQuery(
            IDHandler.getBounds(RelationCategory.PROPERTY, true)[0],
            IDHandler.getBounds(RelationCategory.PROPERTY,false)[1]);

    public static class Executor extends VertexJobConverter {

        private Executor(JanusGraph graph, VertexProgramScanJob job) {
            super(graph, job);
        }

        private Executor(final Executor copy) {
            super(copy);
        }

        @Override
        public List<SliceQuery> getQueries() {
            List<SliceQuery> queries = super.getQueries();
            queries.add(SYSTEM_PROPS_QUERY);
            return queries;
        }

        @Override
        public void workerIterationEnd(ScanMetrics metrics) {
            super.workerIterationEnd(metrics);
        }

        @Override
        public Executor clone() { return new Executor(this); }


    }





}


