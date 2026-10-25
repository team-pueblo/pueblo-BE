package com.pueblo.common.security;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class SensitiveMessageConverter extends ClassicConverter {
    @Override public String convert(ILoggingEvent event) { return SensitiveDataMasker.mask(event.getFormattedMessage()); }
}
