package org.touchhome.common.env.etcd;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Accessors(chain = true)
public class EtcdStat
{
    private boolean isEtcdAvailable = false;

    // etcd locking by key listener
    private Map<String, EtcdLockInfo> lockInfo;

    // watch property updates by name history;
    private Map<String, List<EtcdPropertyUpdate>> watchHistory;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EtcdLockInfo
    {
        private String lockName;

        private Long leaseTTL;

        private Long maxLockTimeout;

        private Long leaseId;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EtcdPropertyUpdate
    {
        private Object value;

        private final Date timeStamp = new Date();
    }
}
