package com.platform.auth.application;

import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationCheckResult;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.domain.VerificationSendResult;

public interface VerificationStore {

    VerificationSendResult storeCode(VerificationPurpose purpose,
                                     VerificationChannel channel,
                                     String target,
                                     String codeHash);

    VerificationCheckResult verifyAndConsume(VerificationPurpose purpose,
                                             VerificationChannel channel,
                                             String target,
                                             String codeHash);
}
