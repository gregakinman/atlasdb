/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.lock.client;

import com.palantir.lock.BlockingMode;
import com.palantir.lock.ForwardingRemoteLockService;
import com.palantir.lock.LockClient;
import com.palantir.lock.LockGroupBehavior;
import com.palantir.lock.LockRequest;
import com.palantir.lock.LockResponse;
import com.palantir.lock.RemoteLockService;

/**
 * This class splits its calls to two clients (which should be the same endpoint) based
 * on whether the call is blocking or non-blocking. This prevents blocking calls to
 * lock from starving out connections for calls like refresh or unlock which should
 * always complete quickly.
 */
public class ClientSplitLockService extends ForwardingRemoteLockService {

    private final RemoteLockService blockingClient;
    private final RemoteLockService nonBlockingClient;

    public ClientSplitLockService(RemoteLockService blockingClient, RemoteLockService nonBlockinClient) {
        this.blockingClient = blockingClient;
        this.nonBlockingClient = nonBlockinClient;
    }

    @Override
    protected RemoteLockService delegate() {
        return nonBlockingClient;
    }

    @Override
    public LockResponse lockAnonymously(LockRequest request) throws InterruptedException {
        return lock(LockClient.ANONYMOUS, request);
    }

    @Override
    public LockResponse lockWithClient(String client, LockRequest request)
            throws InterruptedException {
        return lock(LockClient.of(client), request);
    }

    private LockResponse lock(LockClient client, LockRequest request) throws InterruptedException {
        if (request.getBlockingMode() == BlockingMode.DO_NOT_BLOCK) {
            if (client == LockClient.ANONYMOUS) {
                return nonBlockingClient.lockAnonymously(request);
            } else {
                return nonBlockingClient.lockWithClient(client.getClientId(), request);
            }
        }

        // Let's try sending this request as a non-blocking request.
        if ((request.getLockGroupBehavior() == LockGroupBehavior.LOCK_ALL_OR_NONE)
                && (request.getBlockingMode() != BlockingMode.BLOCK_INDEFINITELY_THEN_RELEASE)) {
            LockRequest.Builder newRequest = LockRequest.builder(request.getLockDescriptors());
            newRequest.doNotBlock();
            newRequest.timeoutAfter(request.getLockTimeout());
            if (request.getVersionId() != null) {
                newRequest.withLockedInVersionId(request.getVersionId());
            }
            final LockResponse response;
            if (client == LockClient.ANONYMOUS) {
                response = nonBlockingClient.lockAnonymously(request);
            } else {
                response = nonBlockingClient.lockWithClient(client.getClientId(), request);
            }
            if (response.success()) {
                return response;
            }
        }

        // No choice but to send it as a blocking request.
        if (client == LockClient.ANONYMOUS) {
            return blockingClient.lockAnonymously(request);
        } else {
            return blockingClient.lockWithClient(client.getClientId(), request);
        }
    }
}
