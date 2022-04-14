package org.touchhome.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Utility {@linkplain PropertyAccessor} to access system properties.
 */
@RequiredArgsConstructor
public class SystemPropertiesAccessor implements PropertyAccessor
{
    private final Environment env;

    public static StandardEvaluationContext createEvaluationContext(Environment env)
    {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.addPropertyAccessor(new SystemPropertiesAccessor(env));
        return context;
    }

    public Class[] getSpecificTargetClasses()
    {
        return null;
    }

    public boolean canRead(EvaluationContext context, Object target, String name)
    {
        return name.equals("systemProperties") || name.equals("environment");
    }

    public TypedValue read(EvaluationContext context, Object target, String name)
    {
        return new TypedValue(name.equals("environment") ? env : new StandardEnvironment().getSystemEnvironment());
    }

    public boolean canWrite(EvaluationContext context, Object target, String name)
    {
        return true;
    }

    public void write(EvaluationContext context, Object target, String name, Object newValue)
    {
        System.setProperty(name, newValue.toString());
    }
}
