package org.touchhome.common.exception;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.touchhome.common.util.FlowMap;
import org.touchhome.common.util.Lang;

public class ServerException extends RuntimeException {
    @Getter
    private FlowMap messageParam;

    public ServerException(String message) {
        super(message);
    }

    public ServerException(Exception ex) {
        super(ex);
    }

    public ServerException(String message, Exception ex) {
        super(message, ex);
    }

    public ServerException(String message, FlowMap messageParam) {
        super(message);
        this.messageParam = messageParam;
    }

    public ServerException(String message, String param0, String value0) {
        this(message, FlowMap.of(param0, value0));
    }

    @Override
    public String toString() {
        return toString(null);
    }

    public String toString(@Nullable FlowMap messageParam) {
        return Lang.getServerMessage(getMessage(), this.messageParam == null ? messageParam : this.messageParam);
    }
}
