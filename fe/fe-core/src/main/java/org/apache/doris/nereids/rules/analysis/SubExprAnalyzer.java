// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.analyzer.Scope;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.memo.Memo;
import org.apache.doris.nereids.trees.expressions.Exists;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.InSubquery;
import org.apache.doris.nereids.trees.expressions.ListQuery;
import org.apache.doris.nereids.trees.expressions.Not;
import org.apache.doris.nereids.trees.expressions.ScalarSubquery;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SubqueryExpr;
import org.apache.doris.nereids.trees.expressions.visitor.DefaultExpressionRewriter;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.planner.PlannerContext;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use the visitor to iterate sub expression.
 */
public class SubExprAnalyzer extends DefaultExpressionRewriter<PlannerContext> {

    private final Scope scope;
    private final CascadesContext cascadesContext;

    public SubExprAnalyzer(Scope scope, CascadesContext cascadesContext) {
        this.scope = scope;
        this.cascadesContext = cascadesContext;
    }

    @Override
    public Expression visitNot(Not not, PlannerContext context) {
        Expression child = not.child();
        if (child instanceof Exists) {
            return visitExistsSubquery(
                    new Exists(((Exists) child).getQueryPlan(), true), context);
        } else if (child instanceof InSubquery) {
            return visitInSubquery(new InSubquery(((InSubquery) child).getCompareExpr(),
                    ((InSubquery) child).getListQuery(), true), context);
        }
        return visit(not, context);
    }

    @Override
    public Expression visitExistsSubquery(Exists exists, PlannerContext context) {
        AnalyzedResult analyzedResult = analyzeSubquery(exists);

        return new Exists(analyzedResult.getLogicalPlan(),
                analyzedResult.getCorrelatedSlots(), exists.isNot());
    }

    @Override
    public Expression visitInSubquery(InSubquery expr, PlannerContext context) {
        AnalyzedResult analyzedResult = analyzeSubquery(expr);

        checkOutputColumn(analyzedResult.getLogicalPlan());
        checkHasGroupBy(analyzedResult);

        return new InSubquery(
                expr.getCompareExpr().accept(this, context),
                new ListQuery(analyzedResult.getLogicalPlan()),
                analyzedResult.getCorrelatedSlots(), expr.isNot());
    }

    @Override
    public Expression visitScalarSubquery(ScalarSubquery scalar, PlannerContext context) {
        AnalyzedResult analyzedResult = analyzeSubquery(scalar);

        checkOutputColumn(analyzedResult.getLogicalPlan());
        checkRootIsAgg(analyzedResult);
        checkHasGroupBy(analyzedResult);

        return new ScalarSubquery(analyzedResult.getLogicalPlan(), analyzedResult.getCorrelatedSlots());
    }

    private void checkOutputColumn(LogicalPlan plan) {
        if (plan.getOutput().size() != 1) {
            throw new AnalysisException("Multiple columns returned by subquery are not yet supported. Found "
                    + plan.getOutput().size());
        }
    }

    private void checkRootIsAgg(AnalyzedResult analyzedResult) {
        if (!analyzedResult.isCorrelated()) {
            return;
        }
        if (!analyzedResult.rootIsAgg()) {
            throw new AnalysisException("The select item in correlated subquery of binary predicate "
                    + "should only be sum, min, max, avg and count. Current subquery: "
                    + analyzedResult.getLogicalPlan());
        }
    }

    private void checkHasGroupBy(AnalyzedResult analyzedResult) {
        if (!analyzedResult.isCorrelated()) {
            return;
        }
        if (analyzedResult.hasGroupBy()) {
            throw new AnalysisException("Unsupported correlated subquery with grouping and/or aggregation "
                    + analyzedResult.getLogicalPlan());
        }
    }

    private AnalyzedResult analyzeSubquery(SubqueryExpr expr) {
        CascadesContext subqueryContext = new Memo(expr.getQueryPlan())
                .newCascadesContext((cascadesContext.getStatementContext()), cascadesContext.getCteContext());
        Scope subqueryScope = genScopeWithSubquery(expr);
        subqueryContext
                .newAnalyzer(Optional.of(subqueryScope))
                .analyze();
        return new AnalyzedResult((LogicalPlan) subqueryContext.getMemo().copyOut(false),
                subqueryScope.getCorrelatedSlots());
    }

    private Scope genScopeWithSubquery(SubqueryExpr expr) {
        return new Scope(getScope().getOuterScope(),
                getScope().getSlots(),
                Optional.ofNullable(expr));
    }

    public Scope getScope() {
        return scope;
    }

    public CascadesContext getCascadesContext() {
        return cascadesContext;
    }

    private static class AnalyzedResult {
        private final LogicalPlan logicalPlan;
        private final List<Slot> correlatedSlots;

        public AnalyzedResult(LogicalPlan logicalPlan, List<Slot> correlatedSlots) {
            this.logicalPlan = Objects.requireNonNull(logicalPlan, "logicalPlan can not be null");
            this.correlatedSlots = correlatedSlots == null ? new ArrayList<>() : ImmutableList.copyOf(correlatedSlots);
        }

        public LogicalPlan getLogicalPlan() {
            return logicalPlan;
        }

        public List<Slot> getCorrelatedSlots() {
            return correlatedSlots;
        }

        public boolean isCorrelated() {
            return !correlatedSlots.isEmpty();
        }

        public boolean rootIsAgg() {
            return logicalPlan instanceof LogicalAggregate;
        }

        public boolean hasGroupBy() {
            if (rootIsAgg()) {
                return !((LogicalAggregate<? extends Plan>) logicalPlan).getGroupByExpressions().isEmpty();
            }
            return false;
        }
    }
}
