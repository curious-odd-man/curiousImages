package com.github.curiousoddman.curious_images.event.payload;

public interface TreeViewUpdatePayload {

    record PersonDelete(long personId) implements TreeViewUpdatePayload {
    }

    record PersonRename(long personId, String newName) implements TreeViewUpdatePayload {
    }

}
