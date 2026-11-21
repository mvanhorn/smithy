/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.rulesengine.logic.cfg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.smithy.rulesengine.language.Endpoint;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.evaluation.type.BooleanType;
import software.amazon.smithy.rulesengine.language.evaluation.type.OptionalType;
import software.amazon.smithy.rulesengine.language.evaluation.type.RecordType;
import software.amazon.smithy.rulesengine.language.evaluation.type.Type;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Reference;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.BooleanEquals;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.Coalesce;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.IsSet;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.LibraryFunction;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.StringEquals;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.Substring;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.Literal;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.RecordLiteral;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.StringLiteral;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.TupleLiteral;
import software.amazon.smithy.rulesengine.language.syntax.rule.Condition;
import software.amazon.smithy.rulesengine.language.syntax.rule.EndpointRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.ErrorRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule;
import software.amazon.smithy.rulesengine.language.syntax.rule.TreeRule;

/**
 * Coalesces bind-then-use patterns in conditions, identifying conditions that bind a variable followed immediately by
 * a condition that uses that variable, and merges them using coalesce.
 */
final class CoalesceTransform {
    private static final Logger LOGGER = Logger.getLogger(CoalesceTransform.class.getName());

    private final Map<CoalesceKey, Condition> coalesceCache = new HashMap<>();
    // Reference counts for each rule, including its result and descendants. Computed once.
    private final Map<Rule, Map<String, Integer>> referenceCountsByRule = new IdentityHashMap<>();
    private int coalesceCount = 0;
    private int cacheHits = 0;
    private int skippedNoZeroValue = 0;
    private int skippedMultipleUses = 0;

    static EndpointRuleSet transform(EndpointRuleSet ruleSet) {
        CoalesceTransform transform = new CoalesceTransform();

        for (Rule rule : ruleSet.getRules()) {
            transform.countSubtreeReferences(rule);
        }

        List<Rule> transformedRules = new ArrayList<>();
        for (Rule rule : ruleSet.getRules()) {
            transformedRules.add(transform.transformRule(rule));
        }

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(String.format(
                    "Coalescing: %d coalesced, %d cache hits, %d skipped (no zero), %d skipped (multiple uses)",
                    transform.coalesceCount,
                    transform.cacheHits,
                    transform.skippedNoZeroValue,
                    transform.skippedMultipleUses));
        }

        return EndpointRuleSet.builder()
                .parameters(ruleSet.getParameters())
                .rules(transformedRules)
                .version(ruleSet.getVersion())
                .componentFactory(ruleSet.getComponentFactory())
                .build();
    }

    private Rule transformRule(Rule rule) {
        // A binding used outside the next condition is not safe to inline. Counts cover this rule's
        // conditions, its result, and nested rules, and were collected in one bottom-up pass.
        Map<String, Integer> localVarUsage = referenceCountsByRule.get(rule);

        Set<Condition> eliminatedConditions = new HashSet<>();
        List<Condition> transformedConditions = transformConditions(
                rule.getConditions(),
                eliminatedConditions,
                localVarUsage);

        if (rule instanceof TreeRule) {
            TreeRule treeRule = (TreeRule) rule;
            List<Rule> transformedNested = new ArrayList<>();
            for (Rule nested : treeRule.getRules()) {
                transformedNested.add(transformRule(nested));
            }
            return TreeRule.builder()
                    .description(rule.getDocumentation().orElse(null))
                    .conditions(transformedConditions)
                    .treeRule(transformedNested);
        }

        // CoalesceTransform only modifies conditions, not endpoints/errors
        return rule.withConditions(transformedConditions);
    }

    private List<Condition> transformConditions(
            List<Condition> conditions,
            Set<Condition> eliminatedConditions,
            Map<String, Integer> localVarUsage
    ) {
        List<Condition> result = new ArrayList<>();

        for (int i = 0; i < conditions.size(); i++) {
            Condition current = conditions.get(i);
            if (eliminatedConditions.contains(current)) {
                continue;
            }

            if (i + 1 < conditions.size() && current.getResult().isPresent()) {
                String var = current.getResult().get().toString();
                Condition next = conditions.get(i + 1);

                if (canCoalesce(var, current, next, localVarUsage)) {
                    result.add(createCoalescedCondition(current, next, var));
                    eliminatedConditions.add(current);
                    eliminatedConditions.add(next);
                    i++; // Skip next
                    continue;
                }
            }

            result.add(current);
        }

        return result;
    }

    // Counts references in this rule and every descendant once. Child totals are merged upward.
    private void countSubtreeReferences(Rule rule) {
        Map<String, Integer> usage = new HashMap<>();
        for (Condition condition : rule.getConditions()) {
            countReferences(condition.getFunction(), usage);
        }
        if (rule instanceof TreeRule) {
            for (Rule nested : ((TreeRule) rule).getRules()) {
                countSubtreeReferences(nested);
                mergeCounts(usage, referenceCountsByRule.get(nested));
            }
        } else {
            countOwnResult(rule, usage);
        }
        referenceCountsByRule.put(rule, usage);
    }

    private static void mergeCounts(Map<String, Integer> into, Map<String, Integer> from) {
        for (Map.Entry<String, Integer> entry : from.entrySet()) {
            into.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static void countOwnResult(Rule rule, Map<String, Integer> usage) {
        if (rule instanceof EndpointRule) {
            Endpoint endpoint = ((EndpointRule) rule).getEndpoint();
            countReferences(endpoint.getUrl(), usage);
            for (List<Expression> values : endpoint.getHeaders().values()) {
                for (Expression value : values) {
                    countReferences(value, usage);
                }
            }
            for (Literal value : endpoint.getProperties().values()) {
                countReferences(value, usage);
            }
        } else if (rule instanceof ErrorRule) {
            countReferences(((ErrorRule) rule).getError(), usage);
        }
    }

    private static void countReferences(Expression expression, Map<String, Integer> usage) {
        for (String ref : expression.getReferences()) {
            usage.merge(ref, 1, Integer::sum);
        }
    }

    private boolean canCoalesce(String var, Condition bind, Condition use, Map<String, Integer> localVarUsage) {
        if (!use.getFunction().getReferences().contains(var)) {
            return false;
        }

        if (use.getFunction().getFunctionDefinition() == IsSet.getDefinition()) {
            return false;
        }

        Integer localUses = localVarUsage.get(var);
        if (localUses == null || localUses > 1) {
            skippedMultipleUses++;
            return false;
        }

        Type type = bind.getFunction().getFunctionDefinition().getReturnType();
        Type innerType = type instanceof OptionalType ? ((OptionalType) type).inner() : type;

        if (innerType instanceof RecordType) {
            skippedNoZeroValue++;
            return false;
        }

        if (!innerType.getZeroValue().isPresent()) {
            skippedNoZeroValue++;
            return false;
        }

        // Splicing coalesce(expr, zero) into a template writes the call into template text.
        if (referencesTemplate(use.getFunction(), var)) {
            return false;
        }

        // A fallible bind ends the rule on None or false. Replacing it with the zero value is sound
        // only when the consuming condition is itself a failure for that zero.
        Literal zero = innerType.getZeroValue().get();
        if ((type instanceof OptionalType || innerType instanceof BooleanType)
                && !failsForZero(use.getFunction(), var, zero)) {
            return false;
        }

        return true;
    }

    private static boolean failsForZero(LibraryFunction use, String var, Literal zero) {
        List<Expression> args = use.getArguments();
        if ((use instanceof StringEquals || use instanceof BooleanEquals) && args.size() >= 2) {
            for (int i = 0; i < 2; i++) {
                Expression other = args.get(1 - i);
                if (isReference(args.get(i), var) && isStaticLiteral(other) && !other.equals(zero)) {
                    return true;
                }
            }
            return false;
        }
        // substring returns empty when the input is too short to include the requested span.
        // An empty string cannot yield a span.
        return use instanceof Substring && !args.isEmpty() && isReference(args.get(0), var);
    }

    private static boolean isStaticLiteral(Expression expression) {
        if (!(expression instanceof Literal)) {
            return false;
        } else if (expression instanceof StringLiteral) {
            return ((StringLiteral) expression).value().isStatic();
        }
        return true;
    }

    private static boolean referencesTemplate(Expression expression, String var) {
        if (expression instanceof StringLiteral) {
            return expression.getReferences().contains(var);
        } else if (expression instanceof TupleLiteral) {
            for (Literal member : ((TupleLiteral) expression).members()) {
                if (referencesTemplate(member, var)) {
                    return true;
                }
            }
        } else if (expression instanceof RecordLiteral) {
            for (Literal member : ((RecordLiteral) expression).members().values()) {
                if (referencesTemplate(member, var)) {
                    return true;
                }
            }
        } else if (expression instanceof LibraryFunction) {
            for (Expression argument : ((LibraryFunction) expression).getArguments()) {
                if (referencesTemplate(argument, var)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isReference(Expression expression, String var) {
        return expression instanceof Reference && ((Reference) expression).getName().toString().equals(var);
    }

    private Condition createCoalescedCondition(Condition bind, Condition use, String var) {
        LibraryFunction bindExpr = bind.getFunction();
        LibraryFunction useExpr = use.getFunction();

        Type type = bindExpr.getFunctionDefinition().getReturnType();
        Type innerType = type instanceof OptionalType ? ((OptionalType) type).inner() : type;
        Literal zero = innerType.getZeroValue().get();

        String bindCanonical = bindExpr.canonicalize().toString();
        String zeroCanonical = zero.toString();
        String useCanonical = useExpr.canonicalize().toString();
        String resultVar = use.getResult().map(Identifier::toString).orElse("");

        CoalesceKey key = new CoalesceKey(bindCanonical, zeroCanonical, useCanonical, var, resultVar);
        Condition cached = coalesceCache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        Expression coalesced = Coalesce.ofExpressions(bindExpr, zero);
        Map<String, Expression> replacements = new HashMap<>();
        replacements.put(var, coalesced);

        Expression replaced = TreeMapper.newReferenceReplacingMapper(replacements).expression(useExpr);
        LibraryFunction canonicalized = ((LibraryFunction) replaced).canonicalize();

        Condition.Builder builder = Condition.builder().fn(canonicalized);
        if (use.getResult().isPresent()) {
            builder.result(use.getResult().get());
        }

        Condition result = builder.build();
        coalesceCache.put(key, result);
        coalesceCount++;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Coalesced #" + coalesceCount + ":\n"
                    + "  " + var + " = " + bind.getFunction() + "\n"
                    + "  " + use.getFunction() + "\n"
                    + "  => " + canonicalized);
        }

        return result;
    }

    private static final class CoalesceKey {
        final String bindFunction;
        final String zeroValue;
        final String useFunction;
        final String replacedVar;
        final String resultVar;
        final int hashCode;

        CoalesceKey(String bindFunction, String zeroValue, String useFunction, String replacedVar, String resultVar) {
            this.bindFunction = bindFunction;
            this.zeroValue = zeroValue;
            this.useFunction = useFunction;
            this.replacedVar = replacedVar;
            this.resultVar = resultVar;
            this.hashCode = Objects.hash(bindFunction, zeroValue, useFunction, replacedVar, resultVar);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            } else if (!(o instanceof CoalesceKey)) {
                return false;
            }
            CoalesceKey that = (CoalesceKey) o;
            return bindFunction.equals(that.bindFunction) && zeroValue.equals(that.zeroValue)
                    && useFunction.equals(that.useFunction)
                    && replacedVar.equals(that.replacedVar)
                    && resultVar.equals(that.resultVar);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
