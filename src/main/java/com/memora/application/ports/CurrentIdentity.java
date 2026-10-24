package com.memora.application.ports;

import com.memora.application.identity.Identity;

@FunctionalInterface
public interface CurrentIdentity {
    Identity identity();
}
