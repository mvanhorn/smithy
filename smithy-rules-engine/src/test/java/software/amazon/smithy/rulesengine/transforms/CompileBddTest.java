/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.rulesengine.transforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.TransformContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.evaluation.RuleEvaluator;
import software.amazon.smithy.rulesengine.language.evaluation.value.Value;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.IsSet;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.ParameterType;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.language.syntax.rule.Condition;
import software.amazon.smithy.rulesengine.language.syntax.rule.EndpointRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.ErrorRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.NoMatchRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule;
import software.amazon.smithy.rulesengine.logic.TestHelpers;
import software.amazon.smithy.rulesengine.logic.bdd.Bdd;
import software.amazon.smithy.rulesengine.traits.EndpointBddTrait;
import software.amazon.smithy.utils.MapUtils;

public class CompileBddTest {

    @Test
    public void compilesAndAttachesBddTrait() throws Exception {
        ShapeId serviceId = ShapeId.from("smithy.example#ExampleService");
        Model model = Model.assembler()
                .discoverModels()
                .addImport(Paths.get(getClass().getResource("compile-bdd.smithy").toURI()))
                .assemble()
                .unwrap();
        TransformContext context = TransformContext.builder()
                .model(model)
                .build();
        Model result = new CompileBdd().transform(context);
        Shape serviceShape = result.expectShape(serviceId);
        assertTrue(serviceShape.hasTrait(EndpointBddTrait.ID));
    }

    @Test
    public void compilesRulesetWhoseErrorReferencesABinding() {
        Map<Identifier, Value> args = MapUtils.of(Identifier.of("R"), Value.stringValue("ax0"));
        EndpointRuleSet ruleSet = load(BINDING_RULESET);
        assertEquals("found x0", RuleEvaluator.evaluate(ruleSet, args).expectStringValue().getValue());

        EndpointBddTrait bdd = CompileBdd.compileBdd(ruleSet);
        assertNotNull(bdd);
        assertEquals("found x0", RuleEvaluator.evaluate(bdd, args).expectStringValue().getValue());
    }

    @Test
    public void compilesRulesetThatReusesALocalName() {
        Map<Identifier, Value> args = MapUtils.of(Identifier.of("R"), Value.stringValue("ax0"));
        EndpointRuleSet ruleSet = load(SHADOWING_RULESET);
        assertEquals("https://x0.example.com",
                RuleEvaluator.evaluate(ruleSet, args).expectEndpointValue().getUrl());

        EndpointBddTrait bdd = CompileBdd.compileBdd(ruleSet);
        assertNotNull(bdd);
        assertEquals("https://x0.example.com",
                RuleEvaluator.evaluate(bdd, args).expectEndpointValue().getUrl());
    }

    @Test
    public void keepsConditionsAResultNeedsAfterRemovingUnreferencedOnes() {
        Parameters parameters = Parameters.builder()
                .addParameter(Parameter.builder().name("R").type(ParameterType.STRING).required(true).build())
                .addParameter(Parameter.builder().name("S").type(ParameterType.STRING).build())
                .build();
        List<Condition> conditions = new ArrayList<>();
        conditions.add(Condition.builder().fn(TestHelpers.stringEquals("R", "drop")).build());
        conditions.add(Condition.builder().fn(TestHelpers.isSet("S")).build());
        conditions.add(Condition.builder()
                .fn(TestHelpers.substring("R", 0, 1, false))
                .result("c")
                .build());
        conditions.add(Condition.builder().fn(TestHelpers.stringEquals("R", "keep")).build());

        List<Rule> results = new ArrayList<>();
        results.add(NoMatchRule.INSTANCE);
        results.add(EndpointRule.builder().endpoint(TestHelpers.endpoint("https://{S}.example.com")));
        results.add(ErrorRule.builder().error("found {c}"));

        // Node 0 is the terminal. Node 1 tests condition 3 and does not reference the others.
        int[] nodes = new int[] {
                -1,
                1,
                -1,
                3,
                Bdd.RESULT_OFFSET + 1,
                -1
        };
        EndpointBddTrait trait = EndpointBddTrait.builder()
                .parameters(parameters)
                .conditions(conditions)
                .results(results)
                .bdd(new Bdd(2, conditions.size(), results.size(), 2, nodes))
                .build();

        EndpointBddTrait trimmed = trait.removeUnreferencedConditions();

        assertEquals(3, trimmed.getConditions().size());
        assertInstanceOf(IsSet.class, trimmed.getConditions().get(0).getFunction());
        assertEquals("c", trimmed.getConditions().get(1).getResult().get().toString());
        assertEquals("stringEquals", trimmed.getConditions().get(2).getFunction().getName());
        assertEquals(2, trimmed.getBdd().getVariable(1));
    }

    private static EndpointRuleSet load(String json) {
        EndpointRuleSet ruleSet = EndpointRuleSet.fromNode(Node.parse(json));
        ruleSet.typeCheck();
        return ruleSet;
    }

    private static final String PARAMETERS = ""
            + "\"R\": {\"type\": \"string\", \"required\": true, \"default\": \"ax0\", \"documentation\": \"d\"},"
            + "\"S\": {\"type\": \"string\", \"documentation\": \"d\"}";

    private static final String BINDING_RULESET = "{"
            + "\"version\": \"1.1\","
            + "\"parameters\": {" + PARAMETERS + "},"
            + "\"rules\": [{"
            + "\"type\": \"error\","
            + "\"conditions\": ["
            + "{\"fn\": \"substring\", \"argv\": [{\"ref\": \"R\"}, 1, 3, false], \"assign\": \"c\"},"
            + "{\"fn\": \"stringEquals\", \"argv\": [{\"ref\": \"c\"}, \"x0\"]}"
            + "],"
            + "\"error\": \"found {c}\""
            + "}]}";

    private static final String SHADOWING_RULESET = "{"
            + "\"version\": \"1.1\","
            + "\"parameters\": {" + PARAMETERS + "},"
            + "\"rules\": ["
            + "{\"type\": \"tree\", \"conditions\": [{\"fn\": \"isSet\", \"argv\": [{\"ref\": \"S\"}]}],"
            + "\"rules\": [{\"type\": \"endpoint\", \"conditions\": ["
            + "{\"fn\": \"substring\", \"argv\": [{\"ref\": \"R\"}, 0, 1, false], \"assign\": \"a\"}"
            + "], \"endpoint\": {\"url\": \"https://{S}.example.com\"}}]},"
            + "{\"type\": \"endpoint\", \"conditions\": ["
            + "{\"fn\": \"substring\", \"argv\": [{\"ref\": \"R\"}, 1, 3, false], \"assign\": \"a\"},"
            + "{\"fn\": \"substring\", \"argv\": [{\"ref\": \"R\"}, 0, 1, false], \"assign\": \"b\"}"
            + "], \"endpoint\": {\"url\": \"https://{a}.example.com\"}}"
            + "]}";
}
