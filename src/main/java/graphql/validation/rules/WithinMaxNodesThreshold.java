package graphql.validation.rules;

import graphql.Internal;
import graphql.execution.AbortExecutionException;
import graphql.language.*;
import graphql.validation.AbstractRule;
import graphql.validation.ValidationContext;
import graphql.validation.ValidationErrorCollector;

@Internal
public class WithinMaxNodesThreshold extends AbstractRule {

    private static final int MAX_NODES = 500; // Taken from the default value used in the latest implementation (https://github.com/graphql-java/graphql-java/blob/master/src/main/java/graphql/introspection/GoodFaithIntrospection.java#L53)
    private int nodeCount = 0;

    public WithinMaxNodesThreshold(ValidationContext validationContext, ValidationErrorCollector validationErrorCollector) {
        super(validationContext, validationErrorCollector);
    }

    private void countNode() {
        nodeCount++;
        if (nodeCount > MAX_NODES) {
            throw new AbortExecutionException(String.format("Validation aborted: too many nodes (max is %d)", MAX_NODES));
        }
    }

    @Override
    public void checkField(Field field) {
        countNode();
    }

}
