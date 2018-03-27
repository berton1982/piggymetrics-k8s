package com.github.galleog.sk.notification.repository.converter;

import com.github.galleog.sk.notification.domain.Frequency;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class FrequencyWriterConverter implements Converter<Frequency, Integer> {

    @Override
    public Integer convert(Frequency frequency) {
        return frequency.getDays();
    }
}
