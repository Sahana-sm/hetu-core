/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner.optimizations.joins;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import io.prestosql.spi.plan.FilterNode;
import io.prestosql.spi.plan.GroupReference;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.plan.InternalPlanVisitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.relational.ProjectNodeUtils.isIdentity;
import static java.util.Objects.requireNonNull;

/**
 * JoinGraph represents sequence of Joins, where nodes in the graph
 * are PlanNodes that are being joined and edges are all equality join
 * conditions between pair of nodes.
 */
public class JoinGraph
{
    private final Optional<Map<Symbol, RowExpression>> assignments;
    private final List<RowExpression> filters;
    private final List<PlanNode> nodes; // nodes in order of their appearance in tree plan (left, right, parent)
    private final Multimap<PlanNodeId, Edge> edges;
    private final PlanNodeId rootId;

    /**
     * Builds all (distinct) {@link JoinGraph}-es whole plan tree.
     */
    public static List<JoinGraph> buildFrom(PlanNode plan)
    {
        return buildFrom(plan, Lookup.noLookup());
    }

    /**
     * Builds {@link JoinGraph} containing {@code plan} node.
     */
    public static JoinGraph buildShallowFrom(PlanNode plan, Lookup lookup)
    {
        JoinGraph graph = plan.accept(new Builder(true, lookup), new Context());
        return graph;
    }

    private static List<JoinGraph> buildFrom(PlanNode plan, Lookup lookup)
    {
        Context context = new Context();
        JoinGraph graph = plan.accept(new Builder(false, lookup), context);
        if (graph.size() > 1) {
            context.addSubGraph(graph);
        }
        return context.getGraphs();
    }

    public JoinGraph(PlanNode node)
    {
        this(ImmutableList.of(node), ImmutableMultimap.of(), node.getId(), ImmutableList.of(), Optional.empty());
    }

    public JoinGraph(
            List<PlanNode> nodes,
            Multimap<PlanNodeId, Edge> edges,
            PlanNodeId rootId,
            List<RowExpression> filters,
            Optional<Map<Symbol, RowExpression>> assignments)
    {
        this.nodes = nodes;
        this.edges = edges;
        this.rootId = rootId;
        this.filters = filters;
        this.assignments = assignments;
    }

    public JoinGraph withAssignments(Map<Symbol, RowExpression> assignments)
    {
        return new JoinGraph(nodes, edges, rootId, filters, Optional.of(assignments));
    }

    public Optional<Map<Symbol, RowExpression>> getAssignments()
    {
        return assignments;
    }

    public JoinGraph withFilter(RowExpression expression)
    {
        ImmutableList.Builder<RowExpression> filters = ImmutableList.builder();
        filters.addAll(this.filters);
        filters.add(expression);

        return new JoinGraph(nodes, edges, rootId, filters.build(), assignments);
    }

    public List<RowExpression> getFilters()
    {
        return filters;
    }

    public PlanNodeId getRootId()
    {
        return rootId;
    }

    public JoinGraph withRootId(PlanNodeId rootId)
    {
        return new JoinGraph(nodes, edges, rootId, filters, assignments);
    }

    public boolean isEmpty()
    {
        return nodes.isEmpty();
    }

    public int size()
    {
        return nodes.size();
    }

    public PlanNode getNode(int index)
    {
        return nodes.get(index);
    }

    public List<PlanNode> getNodes()
    {
        return nodes;
    }

    public Collection<Edge> getEdges(PlanNode node)
    {
        return ImmutableList.copyOf(edges.get(node.getId()));
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        for (PlanNode nodeFrom : nodes) {
            builder.append(nodeFrom.getId())
                    .append(" = ")
                    .append(nodeFrom.toString())
                    .append("\n");
        }
        for (PlanNode nodeFrom : nodes) {
            builder.append(nodeFrom.getId())
                    .append(":");
            for (Edge nodeTo : edges.get(nodeFrom.getId())) {
                builder.append(" ").append(nodeTo.getTargetNode().getId());
            }
            builder.append("\n");
        }

        return builder.toString();
    }

    private JoinGraph joinWith(JoinGraph other, List<JoinNode.EquiJoinClause> joinClauses, Context context, PlanNodeId newRoot)
    {
        for (PlanNode node : other.nodes) {
            checkState(!edges.containsKey(node.getId()), "Node [%s] appeared in two JoinGraphs", node);
        }

        List<PlanNode> nodes = ImmutableList.<PlanNode>builder()
                .addAll(this.nodes)
                .addAll(other.nodes)
                .build();

        ImmutableMultimap.Builder<PlanNodeId, Edge> edges = ImmutableMultimap.<PlanNodeId, Edge>builder()
                .putAll(this.edges)
                .putAll(other.edges);

        List<RowExpression> joinedFilters = ImmutableList.<RowExpression>builder()
                .addAll(this.filters)
                .addAll(other.filters)
                .build();

        for (JoinNode.EquiJoinClause edge : joinClauses) {
            Symbol leftSymbol = edge.getLeft();
            Symbol rightSymbol = edge.getRight();
            checkState(context.containsSymbol(leftSymbol));
            checkState(context.containsSymbol(rightSymbol));

            PlanNode left = context.getSymbolSource(leftSymbol);
            PlanNode right = context.getSymbolSource(rightSymbol);
            edges.put(left.getId(), new Edge(right, leftSymbol, rightSymbol));
            edges.put(right.getId(), new Edge(left, rightSymbol, leftSymbol));
        }

        return new JoinGraph(nodes, edges.build(), newRoot, joinedFilters, Optional.empty());
    }

    private static class Builder
            extends InternalPlanVisitor<JoinGraph, Context>
    {
        // TODO When io.prestosql.sql.planner.optimizations.EliminateCrossJoins is removed, remove 'shallow' flag
        private final boolean shallow;
        private final Lookup lookup;

        private Builder(boolean shallow, Lookup lookup)
        {
            this.shallow = shallow;
            this.lookup = requireNonNull(lookup, "lookup cannot be null");
        }

        @Override
        public JoinGraph visitPlan(PlanNode node, Context context)
        {
            if (!shallow) {
                for (PlanNode child : node.getSources()) {
                    JoinGraph graph = child.accept(this, context);
                    if (graph.size() < 2) {
                        continue;
                    }
                    context.addSubGraph(graph.withRootId(child.getId()));
                }
            }

            for (Symbol symbol : node.getOutputSymbols()) {
                context.setSymbolSource(symbol, node);
            }
            return new JoinGraph(node);
        }

        @Override
        public JoinGraph visitFilter(FilterNode node, Context context)
        {
            JoinGraph graph = node.getSource().accept(this, context);
            return graph.withFilter(node.getPredicate());
        }

        @Override
        public JoinGraph visitJoin(JoinNode node, Context context)
        {
            //TODO: add support for non inner joins
            if (node.getType() != INNER) {
                return visitPlan(node, context);
            }

            JoinGraph left = node.getLeft().accept(this, context);
            JoinGraph right = node.getRight().accept(this, context);

            JoinGraph graph = left.joinWith(right, node.getCriteria(), context, node.getId());

            if (node.getFilter().isPresent()) {
                return graph.withFilter(node.getFilter().get());
            }
            return graph;
        }

        @Override
        public JoinGraph visitProject(ProjectNode node, Context context)
        {
            if (isIdentity(node)) {
                JoinGraph graph = node.getSource().accept(this, context);
                return graph.withAssignments(node.getAssignments().getMap());
            }
            return visitPlan(node, context);
        }

        @Override
        public JoinGraph visitGroupReference(GroupReference node, Context context)
        {
            PlanNode dereferenced = lookup.resolve(node);
            JoinGraph graph = dereferenced.accept(this, context);
            if (isTrivialGraph(graph)) {
                return replacementGraph(dereferenced, node, context);
            }
            return graph;
        }

        private boolean isTrivialGraph(JoinGraph graph)
        {
            return graph.nodes.size() < 2 && graph.edges.isEmpty() && graph.filters.isEmpty() && !graph.assignments.isPresent();
        }

        private JoinGraph replacementGraph(PlanNode oldNode, PlanNode newNode, Context context)
        {
            // TODO optimize when idea is generally approved
            List<Symbol> symbols = context.symbolSources.entrySet().stream()
                    .filter(entry -> entry.getValue() == oldNode)
                    .map(Map.Entry::getKey)
                    .collect(toImmutableList());
            symbols.forEach(symbol -> context.symbolSources.put(symbol, newNode));

            return new JoinGraph(newNode);
        }
    }

    public static class Edge
    {
        private final PlanNode targetNode;
        private final Symbol sourceSymbol;
        private final Symbol targetSymbol;

        public Edge(PlanNode targetNode, Symbol sourceSymbol, Symbol targetSymbol)
        {
            this.targetNode = requireNonNull(targetNode, "targetNode is null");
            this.sourceSymbol = requireNonNull(sourceSymbol, "sourceSymbol is null");
            this.targetSymbol = requireNonNull(targetSymbol, "targetSymbol is null");
        }

        public PlanNode getTargetNode()
        {
            return targetNode;
        }

        public Symbol getSourceSymbol()
        {
            return sourceSymbol;
        }

        public Symbol getTargetSymbol()
        {
            return targetSymbol;
        }
    }

    private static class Context
    {
        private final Map<Symbol, PlanNode> symbolSources = new HashMap<>();

        // TODO When io.prestosql.sql.planner.optimizations.EliminateCrossJoins is removed, remove 'joinGraphs'
        private final List<JoinGraph> joinGraphs = new ArrayList<>();

        public void setSymbolSource(Symbol symbol, PlanNode node)
        {
            symbolSources.put(symbol, node);
        }

        public void addSubGraph(JoinGraph graph)
        {
            joinGraphs.add(graph);
        }

        public boolean containsSymbol(Symbol symbol)
        {
            return symbolSources.containsKey(symbol);
        }

        public PlanNode getSymbolSource(Symbol symbol)
        {
            checkState(containsSymbol(symbol));
            return symbolSources.get(symbol);
        }

        public List<JoinGraph> getGraphs()
        {
            return joinGraphs;
        }
    }
}
