package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonService {
    private final PersonRepository personRepository;
    private final FaceRepository   faceRepository;

    public List<PersonRecord> findAllPersons() {
        return personRepository.findAll();
    }

    public List<Long> getPersonPhotoIds(PersonRecord person) {
        return findFacesByPerson(person)
                .stream()
                .map(FaceRecord::getMediaId)
                .distinct()
                .toList();
    }

    public List<FaceRecord> findFacesByPerson(PersonRecord person) {
        return faceRepository.findByPersonId(person.getId());
    }
}
