package com.pueblo.common.security;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.*;
import java.util.*;

/** Retain diagnostic stack locations, but never emit throwable messages (including causes/suppressed). */
public final class SensitiveThrowableConverter extends ThrowableHandlingConverter {
    @Override public String convert(ILoggingEvent event) {
        StringBuilder output=new StringBuilder();
        append(event.getThrowableProxy(),output,"",Collections.newSetFromMap(new IdentityHashMap<>()),0);
        return output.toString();
    }
    private void append(IThrowableProxy throwable,StringBuilder output,String prefix,Set<IThrowableProxy> seen,int depth) {
        if(throwable==null || depth>=8 || !seen.add(throwable)) return;
        output.append(prefix).append(throwable.getClassName()).append(": [message omitted]\n");
        var frames=throwable.getStackTraceElementProxyArray();
        if(frames!=null) for(int i=0;i<Math.min(frames.length,40);i++) output.append("\tat ").append(SensitiveDataMasker.mask(frames[i].getStackTraceElement().toString())).append('\n');
        if(throwable.getSuppressed()!=null) for(var suppressed:throwable.getSuppressed()) append(suppressed,output,"Suppressed: ",seen,depth+1);
        append(throwable.getCause(),output,"Caused by: ",seen,depth+1);
    }
}
