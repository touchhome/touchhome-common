package org.touchhome.common.env;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

/**
 * Model represent application environment property state;
 */
@Getter
@NoArgsConstructor
public class EnvironmentPropertyModel {
    private String id;

    private String type;

    @JsonIgnore
    private Class<?> rawType;

    private String description;

    private String value;

    private String initValue;

    @Setter
    private String errorValue;

    public EnvironmentPropertyModel(String key, Class type, String description, String value) {
        this.id = key;
        this.rawType = type;
        this.type = type.getSimpleName();
        this.description = StringUtils.trimToEmpty(description);
        setValue(value);
    }

    public void setValue(String value) {
        if (this.initValue == null) {
            this.initValue = value;
        }
        this.value = value;
    }

    public Object getConvertedValue() {
        return WebDocEnvironmentBeanFieldScannerBeanPostProcessor.convertValue(id, value);
    }
}
