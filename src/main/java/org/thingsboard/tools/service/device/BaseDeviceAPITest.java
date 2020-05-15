/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class BaseDeviceAPITest implements DeviceAPITest {

    static String dataAsStr = "{\"id_device\":1,\"msg_id\":1,\"ts\":1589400248,\"data\":[{\"cid\":1,\"fv\":0.9469},{\"cid\":2,\"fv\":0.975},{\"cid\":3,\"fv\":0.9628},{\"cid\":4,\"fv\":0.9818},{\"cid\":5,\"fv\":0.9944},{\"cid\":6,\"fv\":0.9369},{\"cid\":7,\"fv\":0.915},{\"cid\":8,\"fv\":0.8628},{\"cid\":9,\"fv\":0.1018},{\"cid\":10,\"fv\":0.9941},{\"cid\":11,\"fv\":0.1539},{\"cid\":12,\"fv\":0.125}]}";
    static byte[] data = dataAsStr.getBytes(StandardCharsets.UTF_8);

    static ObjectMapper mapper = new ObjectMapper();

    static final int LOG_PAUSE = 1;
    static final int PUBLISHED_MESSAGES_LOG_PAUSE = 5;

    @Value("${device.startIdx}")
    int deviceStartIdx;

    @Value("${device.endIdx}")
    int deviceEndIdx;

    @Value("${rest.url}")
    String restUrl;

    @Value("${rest.username}")
    String username;

    @Value("${rest.password}")
    String password;

    RestClient restClient;

    int deviceCount;

    final ExecutorService httpExecutor = Executors.newFixedThreadPool(100);
    final ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(10);
    final ScheduledExecutorService schedulerLogExecutor = Executors.newScheduledThreadPool(10);

    private final List<DeviceId> deviceIds = Collections.synchronizedList(new ArrayList<>());

    void init() {
        deviceCount = deviceEndIdx - deviceStartIdx;
        restClient = new RestClient(restUrl);
        restClient.login(username, password);
    }

    void destroy() {
        if (!this.httpExecutor.isShutdown()) {
            this.httpExecutor.shutdown();
        }
    }

    String getToken(int token) {
        return String.format("%20d", token).replace(" ", "0");
    }

    @Override
    public void createDevices() throws Exception {
        restClient.login(username, password);
        log.info("Creating {} devices...", deviceCount);
        CountDownLatch latch = new CountDownLatch(deviceCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            httpExecutor.submit(() -> {
                Device device = null;
                try {
                    String token = getToken(tokenNumber);
                    device = restClient.createDevice("Device " + token, "default");
                    restClient.updateDeviceCredentials(device.getId(), token);
                    deviceIds.add(device.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating device", e);
                    if (device != null && device.getId() != null) {
                        restClient.getRestTemplate().delete(restUrl + "/api/device/" + device.getId().getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("{} devices have been created so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        ScheduledFuture<?> tokenRefreshScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                restClient.login(username, password);
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.MINUTES);

        latch.await();
        tokenRefreshScheduleFuture.cancel(true);
        logScheduleFuture.cancel(true);
        log.info("{} devices have been created successfully!", deviceIds.size());
    }

    @Override
    public void removeDevices() throws Exception {
        restClient.login(username, password);
        log.info("Removing {} devices...", deviceIds.size());
        CountDownLatch latch = new CountDownLatch(deviceIds.size());
        AtomicInteger count = new AtomicInteger();
        for (DeviceId deviceId : deviceIds) {
            httpExecutor.submit(() -> {
                try {
                    restClient.getRestTemplate().delete(restUrl + "/api/device/" + deviceId.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting device", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("{} devices have been removed so far...", count.get());
            } catch (Exception ignored) {}
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        ScheduledFuture<?> tokenRefreshScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                restClient.login(username, password);
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.MINUTES);

        latch.await();
        logScheduleFuture.cancel(true);
        tokenRefreshScheduleFuture.cancel(true);
        Thread.sleep(1000);
        log.info("{} devices have been removed successfully! {} were failed for removal!", count.get(), deviceIds.size() - count.get());
    }

}
