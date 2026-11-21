/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.rulesengine.logic.cfg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.ParameterType;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.language.syntax.rule.Condition;
import software.amazon.smithy.rulesengine.language.syntax.rule.EndpointRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule;
import software.amazon.smithy.rulesengine.language.syntax.rule.TreeRule;
import software.amazon.smithy.rulesengine.logic.TestHelpers;

class VariableConsolidationTransformTest {

    @Test
    void doesNotRenameOntoNameAlreadyAssignedInTheSameRule() {
        Rule first = TreeRule.builder()
                .conditions(Condition.builder().fn(TestHelpers.isSet("S")).build())
                .treeRule(EndpointRule.builder()
                        .conditions(assign("R", 0, 1, "a"))
                        .endpoint(TestHelpers.endpoint("https://{S}.example.com")));
        Rule second = EndpointRule.builder()
                .conditions(assign("R", 1, 3, "a"), assign("R", 0, 1, "b"))
                .endpoint(TestHelpers.endpoint("https://{a}.example.com"));

        EndpointRuleSet transformed = VariableConsolidationTransform.transform(ruleset(
                Parameters.builder()
                        .addParameter(required("R"))
                        .addParameter(Parameter.builder().name("S").type(ParameterType.STRING).build())
                        .build(),
                first,
                second));

        Rule rewritten = transformed.getRules().get(1);
        assertEquals("a", rewritten.getConditions().get(0).getResult().get().toString());
        assertEquals("b", rewritten.getConditions().get(1).getResult().get().toString());
        transformed.typeCheck();
    }

    @Test
    void rewritesEliminatedNameToTheParentBindingInEachBranch() {
        Rule branch1 = TreeRule.builder()
                .conditions(assign("R", 0, 1, "a"))
                .treeRule(EndpointRule.builder()
                        .conditions(assign("R", 0, 1, "x"))
                        .endpoint(TestHelpers.endpoint("https://{x}.example.com")));
        Rule branch2 = TreeRule.builder()
                .conditions(assign("S", 0, 1, "b"))
                .treeRule(EndpointRule.builder()
                        .conditions(assign("S", 0, 1, "x"))
                        .endpoint(TestHelpers.endpoint("https://{x}.example.com")));

        EndpointRuleSet transformed = VariableConsolidationTransform.transform(ruleset(
                Parameters.builder().addParameter(required("R")).addParameter(required("S")).build(),
                branch1,
                branch2));

        EndpointRule left = (EndpointRule) ((TreeRule) transformed.getRules().get(0)).getRules().get(0);
        EndpointRule right = (EndpointRule) ((TreeRule) transformed.getRules().get(1)).getRules().get(0);
        assertTrue(left.getConditions().isEmpty());
        assertTrue(right.getConditions().isEmpty());
        assertEquals(Collections.singleton("a"), left.getEndpoint().getUrl().getReferences());
        assertEquals(Collections.singleton("b"), right.getEndpoint().getUrl().getReferences());
        transformed.typeCheck();
    }

    @Test
    void doesNotChainNestedRenameOntoParentTarget() {
        // Earlier siblings make `a` canonical for substring(R, 0, 1) and `x` canonical for substring(R, 1, 2).
        // The later tree renames parent `x = substring(R, 0, 1)` to `a` and child `b = substring(R, 1, 2)` to `x`.
        // Both values stay distinct: the parent value is `a` and the child value is `x`.
        Rule establishF = EndpointRule.builder()
                .conditions(assign("R", 0, 1, "a"))
                .endpoint(TestHelpers.endpoint("https://{a}.example.com"));
        Rule establishG = EndpointRule.builder()
                .conditions(assign("R", 1, 2, "x"))
                .endpoint(TestHelpers.endpoint("https://{x}.example.com"));
        Rule later = TreeRule.builder()
                .conditions(assign("R", 0, 1, "x"))
                .treeRule(EndpointRule.builder()
                        .conditions(assign("R", 1, 2, "b"))
                        .endpoint(TestHelpers.endpoint("https://{x}.{b}.example.com")));

        EndpointRuleSet transformed = VariableConsolidationTransform.transform(ruleset(
                Parameters.builder().addParameter(required("R")).build(),
                establishF,
                establishG,
                later));

        TreeRule tree = (TreeRule) transformed.getRules().get(2);
        assertEquals("a", tree.getConditions().get(0).getResult().get().toString());
        EndpointRule child = (EndpointRule) tree.getRules().get(0);
        assertEquals("x", child.getConditions().get(0).getResult().get().toString());
        assertEquals(TestHelpers.endpoint("https://{a}.{x}.example.com").getUrl(), child.getEndpoint().getUrl());
        transformed.typeCheck();
    }

    @Test
    void consolidatesSiblingBindingsWhenTheNameIsFree() {
        Rule left = EndpointRule.builder()
                .conditions(assign("R", 0, 1, "a"))
                .endpoint(TestHelpers.endpoint("https://example.com"));
        Rule right = EndpointRule.builder()
                .conditions(assign("R", 0, 1, "b"))
                .endpoint(TestHelpers.endpoint("https://example.com"));

        EndpointRuleSet transformed = VariableConsolidationTransform.transform(ruleset(
                Parameters.builder().addParameter(required("R")).build(),
                left,
                right));

        assertEquals("a", transformed.getRules().get(0).getConditions().get(0).getResult().get().toString());
        assertEquals("a", transformed.getRules().get(1).getConditions().get(0).getResult().get().toString());
        transformed.typeCheck();
    }

    private static Condition assign(String param, int start, int stop, String name) {
        return Condition.builder()
                .fn(TestHelpers.substring(param, start, stop, false))
                .result(name)
                .build();
    }

    private static Parameter required(String name) {
        return Parameter.builder().name(name).type(ParameterType.STRING).required(true).build();
    }

    private static EndpointRuleSet ruleset(Parameters parameters, Rule... rules) {
        EndpointRuleSet.Builder builder = EndpointRuleSet.builder().parameters(parameters);
        for (Rule rule : rules) {
            builder.addRule(rule);
        }
        return builder.build();
    }
}
