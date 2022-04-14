package org.touchhome.common.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@RequiredArgsConstructor
public class AnnotationReader {
    private final Environment env;

    private final AnnotationAttributes attributes;

    public String getValue(String name) {
        return replaceEnv(attributes.getString(name));
    }

    public boolean getBoolean(String name) {
        return checkCondition(attributes.getString(name));
    }

    public String replaceEnv(String value) {
        return SpringUtils.replaceValues(value, env::getProperty);
    }

    public Pattern createPattern(String name) {
        return createPattern(Arrays.stream(attributes.getStringArray(name)));
    }

    public Pattern createPattern(Stream<String> patterns) {
        Set<String> values = patterns.map(this::replaceEnv).filter(StringUtils::isNotEmpty).collect(Collectors.toSet());
        if (!values.isEmpty()) {
            String pattern = values.stream().map(path -> "(" + path + ")").collect(Collectors.joining("|"));
            return Pattern.compile(pattern);
        }
        return null;
    }

    public boolean checkCondition(String condition) {
        Expression exp = new SpelExpressionParser().parseExpression(replaceEnv(condition));
        return Boolean.TRUE.equals(exp.getValue(SystemPropertiesAccessor.createEvaluationContext(env), Boolean.class));
    }
}
