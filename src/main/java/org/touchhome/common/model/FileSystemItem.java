package org.touchhome.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class FileSystemItem {
    private boolean dir;
    private boolean empty;
    private String name;
    private String id;
    private Long size;
    private Long lastUpdated;

    @JsonIgnore
    private Map<String, FileSystemItem> childrenMap;

    public Collection<FileSystemItem> getChildren() {
        return childrenMap == null ? null : childrenMap.values();
    }

    public FileSystemItem addChild(String id, boolean appendParentId, Supplier<FileSystemItem> supplier) {
        if (this.childrenMap == null) {
            childrenMap = new HashMap<>();
        }
        if (appendParentId) {
            id = this.id == null ? id : this.id + "~~~" + id;
        }
        if (!childrenMap.containsKey(id)) {
            FileSystemItem child = supplier.get();
            child.id = id;
            childrenMap.put(id, child);
            modifyChildrenKeys(this.id, child);
        }
        return childrenMap.get(id);
    }

    public FileSystemItem addChild(FileSystemItem child) {
        return addChild(true, child);
    }

    public FileSystemItem addChild(boolean appendParentId, FileSystemItem child) {
        if (child != null) {
            return addChild(child.id, appendParentId, () -> child);
        }
        return null;
    }

    public void modifyChildrenKeys(String key, FileSystemItem child) {
        if (child.childrenMap != null) {
            for (FileSystemItem optionModel : child.childrenMap.values()) {
                optionModel.id = key + "~~~" + optionModel.id;
                modifyChildrenKeys(key, optionModel);
            }
        }
    }
}
