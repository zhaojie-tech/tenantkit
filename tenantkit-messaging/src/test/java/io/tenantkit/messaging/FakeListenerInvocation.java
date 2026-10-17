package io.tenantkit.messaging;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;

/**
 * Hand-written fake of the aopalliance {@link MethodInvocation} (the interface
 * Spring AOP hands to advice-chain members): records whether the target ran,
 * lets the test observe context/MDC state <em>inside</em> the advice scope,
 * and can throw a preset failure.
 */
final class FakeListenerInvocation implements MethodInvocation {

    private final Object[] arguments;
    private final Runnable action;
    private final RuntimeException actionFailure;
    private boolean proceeded;

    /** Argument-list-only shape (mirrors {@code onMessage(args)}). */
    FakeListenerInvocation(Object... arguments) {
        this(arguments, null, null);
    }

    /** Listener shape with an in-scope observer. */
    FakeListenerInvocation(Message message, Runnable action) {
        this(new Object[] { message }, action, null);
    }

    /** Listener shape with an in-scope observer that fails. */
    FakeListenerInvocation(Message message, Runnable action, RuntimeException actionFailure) {
        this(new Object[] { message }, action, actionFailure);
    }

    private FakeListenerInvocation(Object[] arguments, Runnable action, RuntimeException actionFailure) {
        this.arguments = arguments;
        this.action = action;
        this.actionFailure = actionFailure;
    }

    boolean proceeded() {
        return this.proceeded;
    }

    @Override
    public Object proceed() {
        this.proceeded = true;
        if (this.action != null) {
            this.action.run();
        }
        if (this.actionFailure != null) {
            throw this.actionFailure;
        }
        return null;
    }

    @Override
    public Object getThis() {
        return this;
    }

    @Override
    public AccessibleObject getStaticPart() {
        return method();
    }

    @Override
    public Object[] getArguments() {
        return this.arguments;
    }

    @Override
    public Method getMethod() {
        return method();
    }

    private static Method method() {
        try {
            return FakeListenerInvocation.class.getDeclaredMethod("listenerShape", Object.class);
        }
        catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unused")
    private void listenerShape(Object message) {
        // signature mirror of MessageListener.onMessage for getMethod()
    }
}
