/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.a2dp;

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.provider.Settings;
import android.util.Log;

import android.os.Handler;

import com.android.bluetooth.R;

import java.util.Arrays;
import java.util.Objects;
/*
 * A2DP Codec Configuration setup.
 */
class A2dpCodecConfig {
    private static final boolean DBG = true;
    private static final String TAG = "A2dpCodecConfig";

    private Context mContext;
    private A2dpNativeInterface mA2dpNativeInterface;

    private BluetoothCodecConfig[] mCodecConfigPriorities;
    private int mA2dpSourceCodecPrioritySbc = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
    private int mA2dpSourceCodecPriorityAac = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
    private int mA2dpSourceCodecPriorityAptx = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
    private int mA2dpSourceCodecPriorityAptxHd = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
    private int mA2dpSourceCodecPriorityLdac = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;

    A2dpCodecConfig(Context context, A2dpNativeInterface a2dpNativeInterface) {
        mContext = context;
        mA2dpNativeInterface = a2dpNativeInterface;
        mCodecConfigPriorities = assignCodecConfigPriorities();
    }

    BluetoothCodecConfig[] codecConfigPriorities() {
        return mCodecConfigPriorities;
    }

    void setCodecConfigPreference(BluetoothDevice device,
                                  BluetoothCodecStatus codecStatus,
                                  BluetoothCodecConfig newCodecConfig) {
        Objects.requireNonNull(codecStatus);

        // Check whether the codecConfig is selectable for this Bluetooth device.
        BluetoothCodecConfig[] selectableCodecs = codecStatus.getCodecsSelectableCapabilities();
        if (!Arrays.asList(selectableCodecs).stream().anyMatch(codec ->
                codec.isMandatoryCodec())) {
            // Do not set codec preference to native if the selectableCodecs not contain mandatory
            // codec. The reason could be remote codec negotiation is not completed yet.
            Log.w(TAG, "setCodecConfigPreference: must have mandatory codec before changing.");
            return;
        }
        if (!codecStatus.isCodecConfigSelectable(newCodecConfig)) {
            Log.w(TAG, "setCodecConfigPreference: invalid codec "
                    + Objects.toString(newCodecConfig));
            return;
        }

        // Check whether the codecConfig would change current codec config.
        int prioritizedCodecType = getPrioitizedCodecType(newCodecConfig, selectableCodecs);
        BluetoothCodecConfig currentCodecConfig = codecStatus.getCodecConfig();
        if (prioritizedCodecType == currentCodecConfig.getCodecType()
                && (prioritizedCodecType != newCodecConfig.getCodecType()
                || (currentCodecConfig.similarCodecFeedingParameters(newCodecConfig)
                && currentCodecConfig.sameCodecSpecificParameters(newCodecConfig)))) {
            // Same codec with same parameters, no need to send this request to native.
            Log.w(TAG, "setCodecConfigPreference: codec not changed.");
            return;
        }

        BluetoothCodecConfig[] codecConfigArray = new BluetoothCodecConfig[1];
        codecConfigArray[0] = newCodecConfig;
        mA2dpNativeInterface.setCodecConfigPreference(device, codecConfigArray);
    }

    void enableOptionalCodecs(BluetoothDevice device, BluetoothCodecConfig currentCodecConfig) {
        if (currentCodecConfig != null && !currentCodecConfig.isMandatoryCodec()) {
            Log.i(TAG, "enableOptionalCodecs: already using optional codec "
                    + currentCodecConfig.getCodecName());
            return;
        }

        BluetoothCodecConfig[] codecConfigArray = assignCodecConfigPriorities();
        if (codecConfigArray == null) {
            return;
        }

        // Set the mandatory codec's priority to default, and remove the rest
        for (int i = 0; i < codecConfigArray.length; i++) {
            BluetoothCodecConfig codecConfig = codecConfigArray[i];
            if( codecConfig == null ) continue;
            if (!codecConfig.isMandatoryCodec()) {
                codecConfigArray[i] = null;
            }
        }

        mA2dpNativeInterface.setCodecConfigPreference(device, codecConfigArray);
    }

    void disableOptionalCodecs(BluetoothDevice device, BluetoothCodecConfig currentCodecConfig) {
        if (currentCodecConfig != null && currentCodecConfig.isMandatoryCodec()) {
            Log.i(TAG, "disableOptionalCodecs: already using mandatory codec.");
            return;
        }

        BluetoothCodecConfig[] codecConfigArray = assignCodecConfigPriorities();
        if (codecConfigArray == null) {
            return;
        }
        // Set the mandatory codec's priority to highest, and remove the rest
        for (int i = 0; i < codecConfigArray.length; i++) {
            BluetoothCodecConfig codecConfig = codecConfigArray[i];
            if( codecConfig == null ) continue;
            if (codecConfig.isMandatoryCodec()) {
                codecConfig.setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST);
            } else {
                codecConfigArray[i] = null;
            }
        }
        mA2dpNativeInterface.setCodecConfigPreference(device, codecConfigArray);
    }

    // Get the codec type of the highest priority of selectableCodecs and codecConfig.
    private int getPrioitizedCodecType(BluetoothCodecConfig codecConfig,
            BluetoothCodecConfig[] selectableCodecs) {
        BluetoothCodecConfig prioritizedCodecConfig = codecConfig;
        for (BluetoothCodecConfig config : selectableCodecs) {
            if (prioritizedCodecConfig == null) {
                prioritizedCodecConfig = config;
            }
            if (config.getCodecPriority() > prioritizedCodecConfig.getCodecPriority()) {
                prioritizedCodecConfig = config;
            }
        }
        return prioritizedCodecConfig.getCodecType();
    }

    // Assign the A2DP Source codec config priorities
    private BluetoothCodecConfig[] assignCodecConfigPriorities() {
        Resources resources = mContext.getResources();


        if (resources == null) {
            return null;
        }

        final ContentResolver resolver = mContext.getContentResolver();

        int value;
        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_sbc);
            if( Settings.Global.getInt(resolver, Settings.Global.BAIKALOS_BT_SBC_PRIORITY, 0) == 1 ) {

                int ldac_prio = resources.getInteger(R.integer.a2dp_source_codec_priority_ldac);
                int aptx_hd_prio = resources.getInteger(R.integer.a2dp_source_codec_priority_aptx_hd);
                if( ldac_prio == 0 && aptx_hd_prio == 0 ) {
                    value = BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST-1;
                } else if( ldac_prio == 0 ) {
                    value = aptx_hd_prio + 1;
                } else if( aptx_hd_prio == 0 ) {
                    value = ldac_prio-1;
                } else {
                    value = aptx_hd_prio + (ldac_prio - aptx_hd_prio)/2;
                }
            }
            if( Settings.Global.getInt(resolver, Settings.Global.BAIKALOS_BT_SBC_DISABLED, 0) == 1 ) {
                value = BluetoothCodecConfig.CODEC_PRIORITY_DISABLED;
            }
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            mA2dpSourceCodecPrioritySbc = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_aac);
            if( Settings.Global.getInt(resolver, Settings.Global.BAIKALOS_BT_AAC_DISABLED, 0) == 1 ) {
                value = BluetoothCodecConfig.CODEC_PRIORITY_DISABLED;
            }
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            mA2dpSourceCodecPriorityAac = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_aptx);
            if( Settings.Global.getInt(resolver, Settings.Global.BAIKALOS_BT_APTX_DISABLED, 0) == 1 ) {
                value = BluetoothCodecConfig.CODEC_PRIORITY_DISABLED;
            }
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            mA2dpSourceCodecPriorityAptx = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_aptx_hd);
            if( Settings.Global.getInt(resolver, Settings.Global.BAIKALOS_BT_APTX_HD_DISABLED, 0) == 1 ) {
                value = BluetoothCodecConfig.CODEC_PRIORITY_DISABLED;
            }
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            mA2dpSourceCodecPriorityAptxHd = value;
        }

        try {
            value = resources.getInteger(R.integer.a2dp_source_codec_priority_ldac);
            if( Settings.Global.getInt(resolver, Settings.Global.BAIKALOS_BT_LDAC_DISABLED, 0) == 1 ) {
                value = BluetoothCodecConfig.CODEC_PRIORITY_DISABLED;
            }
        } catch (NotFoundException e) {
            value = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        }
        if ((value >= BluetoothCodecConfig.CODEC_PRIORITY_DISABLED) && (value
                < BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)) {
            mA2dpSourceCodecPriorityLdac = value;
        }

        BluetoothCodecConfig codecConfig;
        BluetoothCodecConfig[] codecConfigArray =
                new BluetoothCodecConfig[BluetoothCodecConfig.SOURCE_CODEC_TYPE_MAX];
        codecConfig = new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                mA2dpSourceCodecPrioritySbc, BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE, BluetoothCodecConfig
                .CHANNEL_MODE_NONE, 0 /* codecSpecific1 */,
                0 /* codecSpecific2 */, 0 /* codecSpecific3 */, 0 /* codecSpecific4 */);
        codecConfigArray[0] = codecConfig;
        codecConfig = new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                mA2dpSourceCodecPriorityAac, BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE, BluetoothCodecConfig
                .CHANNEL_MODE_NONE, 0 /* codecSpecific1 */,
                0 /* codecSpecific2 */, 0 /* codecSpecific3 */, 0 /* codecSpecific4 */);
        codecConfigArray[1] = codecConfig;
        codecConfig = new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                mA2dpSourceCodecPriorityAptx, BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE, BluetoothCodecConfig
                .CHANNEL_MODE_NONE, 0 /* codecSpecific1 */,
                0 /* codecSpecific2 */, 0 /* codecSpecific3 */, 0 /* codecSpecific4 */);
        codecConfigArray[2] = codecConfig;
        codecConfig = new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                mA2dpSourceCodecPriorityAptxHd, BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE, BluetoothCodecConfig
                .CHANNEL_MODE_NONE, 0 /* codecSpecific1 */,
                0 /* codecSpecific2 */, 0 /* codecSpecific3 */, 0 /* codecSpecific4 */);
        codecConfigArray[3] = codecConfig;
        codecConfig = new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                mA2dpSourceCodecPriorityLdac, BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE, BluetoothCodecConfig
                .CHANNEL_MODE_NONE, 0 /* codecSpecific1 */,
                0 /* codecSpecific2 */, 0 /* codecSpecific3 */, 0 /* codecSpecific4 */);
        codecConfigArray[4] = codecConfig;

        return codecConfigArray;
    }
}

