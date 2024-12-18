/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.util;

import org.apache.flink.api.common.JobID;
import org.apache.flink.testutils.logging.LoggerAuditingExtension;
import org.apache.flink.util.MdcUtils.MdcCloseable;
import org.apache.flink.util.concurrent.Executors;
import org.apache.flink.util.function.ThrowingConsumer;

import org.apache.logging.log4j.core.LogEvent;
import org.assertj.core.api.AbstractObjectAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.answers.CallsRealMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.util.MdcUtils.asContextData;
import static org.apache.flink.util.MdcUtils.wrapCallable;
import static org.apache.flink.util.MdcUtils.wrapRunnable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.isNull;
import static org.slf4j.event.Level.DEBUG;

/** Tests for the {@link MdcUtils}. */
class MdcUtilsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MdcUtilsTest.class);
    private static final Runnable LOGGING_RUNNABLE = () -> LOGGER.info("ignore");

    @RegisterExtension
    public final LoggerAuditingExtension loggerExtension =
            new LoggerAuditingExtension(MdcUtilsTest.class, DEBUG);

    @Test
    void testContextRestorationWorksWithNullContext() {
        try (MockedStatic<MDC> mockStatic = Mockito.mockStatic(MDC.class, new CallsRealMethods())) {
            mockStatic.when(MDC::getCopyOfContextMap).thenReturn(null);
            mockStatic.when(() -> MDC.setContextMap(isNull()))
                    .thenThrow(NullPointerException.class);

            MdcCloseable restoreContext = MdcUtils.withContext(Collections.singletonMap("k", "v"));
            assertThat(MDC.get("k")).isEqualTo("v");
            assertDoesNotThrow(restoreContext::close);
        }
    }

    @Test
    void testJobIDAsContext() {
        JobID jobID = new JobID();
        assertThat(MdcUtils.asContextData(jobID))
                .isEqualTo(Collections.singletonMap("flink-job-id", jobID.toHexString()));
    }

    @Test
    void testMdcCloseableAddsJobId() throws Exception {
        assertJobIDLogged(
                jobID -> {
                    try (MdcCloseable ignored = MdcUtils.withContext(asContextData(jobID))) {
                        LOGGER.warn("ignore");
                    }
                });
    }

    @Test
    void testMdcCloseableRemovesJobId() {
        JobID jobID = new JobID();
        try (MdcCloseable ignored = MdcUtils.withContext(asContextData(jobID))) {
            // ...
        }
        LOGGER.warn("with-job");
        assertJobIdLogged(null);
    }

    @Test
    void testWrapRunnable() throws Exception {
        assertJobIDLogged(jobID -> wrapRunnable(asContextData(jobID), LOGGING_RUNNABLE).run());
    }

    @Test
    void testWrapCallable() throws Exception {
        assertJobIDLogged(
                jobID ->
                        wrapCallable(
                                        asContextData(jobID),
                                        () -> {
                                            LOGGER.info("ignore");
                                            return null;
                                        })
                                .call());
    }

    @Test
    void testScopeExecutor() throws Exception {
        assertJobIDLogged(
                jobID ->
                        MdcUtils.scopeToJob(jobID, Executors.directExecutor())
                                .execute(LOGGING_RUNNABLE));
    }

    @Test
    void testScopeExecutorService() throws Exception {
        assertJobIDLogged(
                jobID ->
                        MdcUtils.scopeToJob(jobID, Executors.newDirectExecutorService())
                                .submit(LOGGING_RUNNABLE)
                                .get());
    }

    @Test
    void testScopeScheduledExecutorService() throws Exception {
        ScheduledExecutorService ses =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            assertJobIDLogged(
                    jobID ->
                            MdcUtils.scopeToJob(jobID, ses)
                                    .schedule(LOGGING_RUNNABLE, 1L, TimeUnit.MILLISECONDS)
                                    .get());
        } finally {
            ses.shutdownNow();
        }
    }

    private void assertJobIDLogged(ThrowingConsumer<JobID, Exception> action) throws Exception {
        JobID jobID = new JobID();
        action.accept(jobID);
        assertJobIdLogged(jobID);
    }

    private void assertJobIdLogged(JobID jobId) {
        AbstractObjectAssert<?, Object> extracting =
                assertThat(loggerExtension.getEvents())
                        .singleElement()
                        .extracting(LogEvent::getContextData)
                        .extracting(m -> m.getValue("flink-job-id"));
        if (jobId == null) {
            extracting.isNull();
        } else {
            extracting.isEqualTo(jobId.toHexString());
        }
    }
}
