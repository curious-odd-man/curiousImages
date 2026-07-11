package com.github.curiousoddman.curious_images.event.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEvent;

/**
 * Fired whenever a person record is deleted (e.g. an orphaned person cleaned up from
 * {@code FacePickerController#handleOrphanedPersons}), so any UI showing that person — such as
 * the library tree's People section — can remove it immediately instead of waiting for a full
 * refresh. Mirrors {@link PersonRenamedEvent}.
 */
@Getter
public class PersonDeletedEvent extends ApplicationEvent {
    private final long personId;

    public PersonDeletedEvent(Object source, long personId) {
        super(source);
        this.personId = personId;
    }
}
