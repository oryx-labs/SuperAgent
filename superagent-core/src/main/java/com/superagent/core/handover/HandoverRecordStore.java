package com.superagent.core.handover;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for {@link HandoverRecord}. The active backend is chosen in superagent-storage. */
public interface HandoverRecordStore {

    HandoverRecord save(HandoverRecord record);

    Optional<HandoverRecord> find(String id);

    /** Newest first. */
    List<HandoverRecord> list(int limit);
}
