/**
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.transit_data_federation.impl.federated;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.onebusaway.transit_data_federation.services.bundle.BundleManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TransitDataServiceBundleBlockInterceptor {

    private final Logger _log = LoggerFactory.getLogger(TransitDataServiceBundleBlockInterceptor.class);
    
    @Autowired(required=false)
    private BundleManagementService _bundleManagementService;
    
    private int _blockedRequestCounter = 0;

    @Before("execution(* org.onebusaway.transit_data_federation.impl.federated.TransitDataServiceImpl.*(..))")
    private void blockUntilBundleIsReady() {
        try {
            while (_bundleManagementService != null && !_bundleManagementService.bundleIsReady()) {
                _blockedRequestCounter++;
                
                // only print this every 25 times so we don't fill up the logs!
                if (_blockedRequestCounter > 25) {
                    _log.warn("Bundle is not ready or none is loaded--we've blocked 25 TDS requests since last log event.");
                    _blockedRequestCounter = 0;
                }

                synchronized (this) {
                    Thread.sleep(250);
                    Thread.yield();
                }
            }
        } catch (InterruptedException e) {
            return;
        }
    }
}