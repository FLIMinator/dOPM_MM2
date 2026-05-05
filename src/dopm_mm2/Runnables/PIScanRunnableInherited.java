/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Runnables;

import dopm_mm2.Devices.DeviceSettingsManager;
import dopm_mm2.Devices.PIStage;
import dopm_mm2.GUI.dOPM_hostframe;
import dopm_mm2.acquisition.MDAProgressManager;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.data.Datastore;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.display.DisplayWindow;

/**
 * PI mirror-scan runnable using the shared AbstractAcquisitionRunnable workflow.
 *
 * Key design points:
 *
 * 1. Tango XY always defines the sample/FOV position.
 *
 * 2. The PI mirror has view-specific centre positions. The parent class calls
 *    currentAcq.setCurrentView(1/2) before runSingleView(...), so this class
 *    reads the current PI mirror position inside runSingleView(...) and scans
 *    relative to that view-specific value.
 *
 * 3. PI trigger geometry is cached. If the trigger start, end, and distance are
 *    unchanged between channels, the expensive PI serial trigger reconfiguration
 *    is skipped.
 *
 * 4. Sync-readout mode is handled separately from global-reset mode:
 *
 *      Global reset:
 *          one useful trigger event per acquired image
 *
 *      Sync readout:
 *          trigger events are frame boundaries
 *          N image planes require N + 1 trigger events
 *
 * Supported trigger modes:
 *   Mode 0: external global reset
 *   Mode 1: external synchronous readout
 *
 * Mode 2 is intentionally rejected for PI mirror scanning until a true
 * single-start-trigger PI implementation is added.
 */
public class PIScanRunnableInherited extends AbstractAcquisitionRunnable {

    private final int PIDeviceID;

    /** Fixed mirror travel margin before the trigger range begins. */
    private static final double SCAN_UNDERSHOOT_UM = 10.0;

    /** Fixed mirror travel margin after the trigger range ends. */
    private static final double SCAN_OVERSHOOT_UM = 10.0;

    /**
     * Tolerance for deciding whether cached PI trigger geometry is still valid.
     *
     * Units are mm because PI trigger range/distance are sent to the controller
     * in mm.
     */
    private static final double PI_TRIGGER_CACHE_TOL_MM = 1e-5;

    /** Cached PI trigger geometry from the previous runSingleView call. */
    private Double lastTriggerStartMillim = null;
    private Double lastTriggerEndMillim = null;
    private Double lastTriggerDistanceMillim = null;

    public PIScanRunnableInherited(dOPM_hostframe frame_ref,
            MDAProgressManager acqProgressMgr) {
        super(frame_ref, acqProgressMgr);
        PIDeviceID = 1;
    }

    private void logPIScanTiming(String label, long ticMs) {
        runnableLogger.info(String.format(
                "TIMING | PIScan | %s | %d ms",
                label,
                System.currentTimeMillis() - ticMs));
    }

    /**
     * Return true if the requested PI trigger geometry differs from the cached
     * controller geometry.
     */
    private boolean shouldConfigurePITrigger(
            double triggerStartMillim,
            double triggerEndMillim,
            double triggerDistanceMillim) {

        if (lastTriggerStartMillim == null
                || lastTriggerEndMillim == null
                || lastTriggerDistanceMillim == null) {
            return true;
        }

        return Math.abs(lastTriggerStartMillim - triggerStartMillim)
                    > PI_TRIGGER_CACHE_TOL_MM
                || Math.abs(lastTriggerEndMillim - triggerEndMillim)
                    > PI_TRIGGER_CACHE_TOL_MM
                || Math.abs(lastTriggerDistanceMillim - triggerDistanceMillim)
                    > PI_TRIGGER_CACHE_TOL_MM;
    }

    /**
     * Store the latest PI trigger geometry after successful configuration.
     */
    private void cachePITriggerGeometry(
            double triggerStartMillim,
            double triggerEndMillim,
            double triggerDistanceMillim) {

        lastTriggerStartMillim = triggerStartMillim;
        lastTriggerEndMillim = triggerEndMillim;
        lastTriggerDistanceMillim = triggerDistanceMillim;
    }

    /**
     * Clear cached trigger geometry after an error or if we cannot trust the
     * controller state.
     */
    private void invalidatePITriggerCache() {
        lastTriggerStartMillim = null;
        lastTriggerEndMillim = null;
        lastTriggerDistanceMillim = null;
    }

    @Override
    public void runSingleView(double opmAngle) throws Exception {

        long runSingleViewTic = tic();

        // ---------------------------------------------------------------------
        // Validate camera trigger mode
        // ---------------------------------------------------------------------

        final int triggerMode = deviceSettings.getTriggerMode();

        final boolean globalResetMode =
                triggerMode == DeviceSettingsManager.TRIGGER_EXTERNAL_GLOBAL;

        final boolean syncReadoutMode =
                triggerMode == DeviceSettingsManager.TRIGGER_EXTERNAL_SYNCREADOUT;

        final boolean internalClockMode =
                triggerMode == DeviceSettingsManager.TRIGGER_START_INTERNAL_CLOCK;

        if (internalClockMode) {
            throw new UnsupportedOperationException(String.format(
                    "PI mirror scan does not yet support trigger mode 2 / start-trigger + internal clock. Current mode is %d (%s).",
                    triggerMode,
                    deviceSettings.getTriggerModeLabel()));
        }

        if (!globalResetMode && !syncReadoutMode) {
            throw new UnsupportedOperationException(String.format(
                    "PI mirror scan currently supports only trigger modes 0 and 1. Current mode is %d (%s).",
                    triggerMode,
                    deviceSettings.getTriggerModeLabel()));
        }

        DisplayWindow display = null;

        // ---------------------------------------------------------------------
        // Read scan settings
        // ---------------------------------------------------------------------

        final double scanLengthUm = deviceSettings.getMirrorScanLength();
        final double triggerDistanceUm = deviceSettings.getMirrorTriggerDistance();
        final double triggerDistanceMillim = triggerDistanceUm * 1e-3;
        final double scanSpeed = deviceSettings.getMirrorStageCurrentScanSpeed();

        if (triggerDistanceUm <= 0) {
            throw new IllegalArgumentException(
                    "Mirror trigger distance must be > 0");
        }

        if (scanLengthUm <= 0) {
            throw new IllegalArgumentException(
                    "Mirror scan length must be > 0");
        }

        // ---------------------------------------------------------------------
        // Critical PI-view-relative scan centre
        // ---------------------------------------------------------------------

        /*
         * The parent class has already called currentAcq.setCurrentView(1/2)
         * before entering this method.
         *
         * For PI mirror scanning, the dOPM view preset can move the PI mirror to
         * a view-specific centre. Therefore, the scan must be centred on the
         * CURRENT mirror position after the view has been set, not on the
         * pre-view stored mirror position.
         */
        final double viewMirrorCenterUm = core_.getPosition(mirrorStage);

        runnableLogger.info(String.format(
                "PI mirror scan | mode=%s | currentView=%d | storedMirrorStart=%.5f um | viewMirrorCenter=%.5f um | targetLength=%.3f um | triggerDistance=%.5f um | scanSpeed=%.6f mm/s | viewAngle=%.3f",
                deviceSettings.getTriggerModeLabel(),
                currentAcq != null ? currentAcq.getCurrentView() : -1,
                startingMirrorPositionUm,
                viewMirrorCenterUm,
                scanLengthUm,
                triggerDistanceUm,
                scanSpeed,
                opmAngle));

        // ---------------------------------------------------------------------
        // Derive scan geometry around the view-specific mirror centre
        // ---------------------------------------------------------------------

        /*
         * nFrames is the number of images to acquire.
         *
         * Keep this as the number of plane intervals in the requested scan
         * length. The trigger-event count may differ by mode.
         */
        final int nFrames =
                Math.max(1, (int) Math.floor(scanLengthUm / triggerDistanceUm));

        final double triggerScanStartUm =
                viewMirrorCenterUm - scanLengthUm / 2.0;

        final double targetTriggerScanEndUm =
                viewMirrorCenterUm + scanLengthUm / 2.0;

        /*
         * Isolated sync-readout fix:
         *
         * In synchronous readout, trigger events are frame boundaries.
         * N frames require N + 1 trigger events.
         *
         * Assuming the PI emits triggers at:
         *
         *      start, start + d, ..., end
         *
         * then a trigger range of length N*d gives N + 1 trigger boundary
         * events and therefore N acquired frame intervals.
         *
         * In global-reset mode, preserve the existing PI behaviour as closely
         * as possible, because that path has already been shown to work after
         * slowing the scan speed.
         */
        final int requiredTriggerEvents;
        final double triggerScanEndUm;

        if (syncReadoutMode) {
            requiredTriggerEvents = nFrames + 1;

            triggerScanEndUm =
                    triggerScanStartUm + triggerDistanceUm * nFrames;

        } else {
            requiredTriggerEvents = nFrames;

            /*
             * Existing global-reset PI behaviour:
             * round the actual trigger end down to an integer number of
             * trigger-distance intervals within the requested target range.
             */
            triggerScanEndUm =
                    triggerScanStartUm
                    + triggerDistanceUm * Math.floor(
                            (targetTriggerScanEndUm - triggerScanStartUm)
                            / triggerDistanceUm);
        }

        final double acquiredScanLengthUm =
                nFrames * triggerDistanceUm;

        final double scanStartUm =
                triggerScanStartUm - SCAN_UNDERSHOOT_UM;

        final double scanEndUm =
                triggerScanEndUm + SCAN_OVERSHOOT_UM;

        final double triggerScanStartMillim = triggerScanStartUm * 1e-3;
        final double triggerScanEndMillim = triggerScanEndUm * 1e-3;

        final double effectiveFPS =
                1.0 / (triggerDistanceMillim / scanSpeed);

        runnableLogger.info(String.format(
                "PI trigger/frame convention | mode=%s | currentView=%d | nFrames=%d | requiredTriggerEvents=%d | triggerDistance=%.5f um | triggerStart=%.5f um | targetTriggerEnd=%.5f um | actualTriggerEnd=%.5f um | scanStart=%.5f um | scanEnd=%.5f um | effectiveFPS=%.3f",
                deviceSettings.getTriggerModeLabel(),
                currentAcq != null ? currentAcq.getCurrentView() : -1,
                nFrames,
                requiredTriggerEvents,
                triggerDistanceUm,
                triggerScanStartUm,
                targetTriggerScanEndUm,
                triggerScanEndUm,
                scanStartUm,
                scanEndUm,
                effectiveFPS));

        // ---------------------------------------------------------------------
        // Configure PI trigger output only if geometry changed
        // ---------------------------------------------------------------------

        long triggerSetupTic = tic();

        final boolean configurePITrigger =
                shouldConfigurePITrigger(
                        triggerScanStartMillim,
                        triggerScanEndMillim,
                        triggerDistanceMillim);

        if (configurePITrigger) {
            try {
                runnableLogger.info(String.format(
                        "Configuring PI trigger geometry | start=%.5f mm | end=%.5f mm | dist=%.5f mm | requiredTriggerEvents=%d",
                        triggerScanStartMillim,
                        triggerScanEndMillim,
                        triggerDistanceMillim,
                        requiredTriggerEvents));

                /*
                 * Conservative full setup path. It runs when geometry changes,
                 * for example between View 1 and View 2, or between MDA
                 * positions where the view mirror centre differs.
                 */
                PIStage.setupPITriggering(mirrorStagePort, PIDeviceID);

                /*
                 * Retained from the original working PI implementation.
                 */
                PIStage.stopPIStage(mirrorStagePort);

                PIStage.setPITriggerDistance(
                        mirrorStagePort,
                        PIDeviceID,
                        triggerDistanceMillim);

                PIStage.setPITriggerRange(
                        mirrorStagePort,
                        PIDeviceID,
                        new double[]{
                            triggerScanStartMillim,
                            triggerScanEndMillim
                        });

                cachePITriggerGeometry(
                        triggerScanStartMillim,
                        triggerScanEndMillim,
                        triggerDistanceMillim);

            } catch (Exception e) {
                invalidatePITriggerCache();
                runnableLogger.severe(
                        "Failed during PI trigger setup: " + e.getMessage());
                throw e;
            }

            logPIScanTiming("triggerSetupTotalConfigured", triggerSetupTic);

            try {
                String[] piSettings = PIStage.viewTriggerSettings(mirrorStagePort);
                runnableLogger.info("PI trigger settings after setup: "
                        + String.join(" | ", piSettings));
            } catch (Exception e) {
                runnableLogger.warning(
                        "Could not log PI trigger settings after setup: "
                        + e.getMessage());
            }

        } else {
            runnableLogger.info(String.format(
                    "Skipping PI trigger reconfiguration; geometry unchanged | start=%.5f mm | end=%.5f mm | dist=%.5f mm | requiredTriggerEvents=%d",
                    triggerScanStartMillim,
                    triggerScanEndMillim,
                    triggerDistanceMillim,
                    requiredTriggerEvents));

            logPIScanTiming("triggerSetupTotalSkipped", triggerSetupTic);
        }

        // ---------------------------------------------------------------------
        // Move mirror to scan start
        // ---------------------------------------------------------------------

        long moveToStartTic = tic();

        try {
            /*
             * Move to the undershoot position at travel speed.
             *
             * This does not reset to the acquisition centre. It only moves from
             * the current/end position back to the scan-start side for this
             * view-specific relative scan.
             */
            core_.setProperty(
                    mirrorStage,
                    "Velocity",
                    deviceSettings.getMirrorStageTravelSpeed());

            core_.waitForDevice(mirrorStage);
            core_.setPosition(mirrorStage, scanStartUm);
            core_.waitForDevice(mirrorStage);

        } catch (Exception e) {
            runnableLogger.severe(
                    "Failed to move PI mirror to scan start: "
                    + e.getMessage());
            throw e;
        }

        logPIScanTiming("moveToScanStart", moveToStartTic);

        // ---------------------------------------------------------------------
        // Create datastore
        // ---------------------------------------------------------------------

        long datastoreTic = tic();

        Datastore store;

        if (frame_.isSaveImgToDisk()) {
            try {
                PropertyMap myPropertyMap = PropertyMaps.builder()
                        .putString("scan type", "PI mirror scanning")
                        .putString("mode", globalResetMode
                                ? "ExternalGlobalReset"
                                : "ExternalSyncReadout")
                        .putInteger("currentView",
                                currentAcq != null ? currentAcq.getCurrentView() : -1)
                        .putDouble("stored mirror start um", startingMirrorPositionUm)
                        .putDouble("view mirror center um", viewMirrorCenterUm)
                        .putDouble("trigger distance um", triggerDistanceUm)
                        .putDouble("trigger distance mm", triggerDistanceMillim)
                        .putDouble("requested scan length um", scanLengthUm)
                        .putDouble("acquired scan length um", acquiredScanLengthUm)
                        .putDouble("scan start um", scanStartUm)
                        .putDouble("scan end um", scanEndUm)
                        .putDouble("trigger scan start um", triggerScanStartUm)
                        .putDouble("trigger scan end um", triggerScanEndUm)
                        .putDouble("target trigger scan end um", targetTriggerScanEndUm)
                        .putDouble("scan speed mm/s", scanSpeed)
                        .putInteger("nFrames", nFrames)
                        .putInteger("required trigger events", requiredTriggerEvents)
                        .putBoolean("pi trigger reconfigured", configurePITrigger)
                        .build();

                /*
                 * d' in the literature: plane spacing in mirror-normal
                 * coordinates.
                 */
                double zprimeSpacing =
                        deviceSettings.lateralScanToMirrorNormal(
                                triggerDistanceUm);

                SummaryMetadata metadata =
                        mm_.data().summaryMetadataBuilder()
                                .zStepUm(zprimeSpacing)
                                .build();

                store = createDatastore(metadata, myPropertyMap);

            } catch (IOException ie) {
                throw ie;
            } catch (Exception e) {
                throw new Exception(
                        "Unknown error creating PI mirror datastore: "
                        + e.getMessage());
            }
        } else {
            store = mm_.data().createRAMDatastore();
            display = mm_.displays().createDisplay(store);
        }

        logPIScanTiming("createDatastore", datastoreTic);

        // ---------------------------------------------------------------------
        // Arm camera sequence
        // ---------------------------------------------------------------------

        long seqPrepTic = tic();
        core_.prepareSequenceAcquisition(camName);
        logPIScanTiming("prepareSequenceAcquisition", seqPrepTic);

        long seqStartTic = tic();
        core_.startSequenceAcquisition(camName, nFrames, 0, true);
        logPIScanTiming("startSequenceAcquisition", seqStartTic);

        long seqReadyTic = tic();
        int readyCheck = 0;

        while (!core_.isSequenceRunning(camName) && readyCheck < 500) {
            Thread.sleep(10);
            readyCheck++;
        }

        logPIScanTiming("waitForSequenceRunning", seqReadyTic);

        // ---------------------------------------------------------------------
        // Execute scan and collect frames
        // ---------------------------------------------------------------------

        try {
            /*
             * Even when trigger geometry is cached, trigger output must still be
             * enabled for each scan and disabled in finally.
             */
            long trigEnableTic = tic();
            PIStage.setPITriggerEnable(mirrorStagePort, PIDeviceID, 1);
            logPIScanTiming("setPITriggerEnableOn", trigEnableTic);

            if (syncReadoutMode) {
                /*
                 * Sync-readout mode:
                 * The trigger events are the frame boundaries. We therefore
                 * opened the PI trigger range above to provide N + 1 trigger
                 * events for N images.
                 *
                 * Match the Tango sync-readout pathway by explicitly unblanking
                 * just before the scan.
                 */
                long blankingTic = tic();
                core_.setProperty(DAQDOPort, "Blank on", "Low");
                core_.setProperty(DAQDOPort, "Blanking", "Off");
                logPIScanTiming("syncReadoutBlankingOff", blankingTic);

                long settleTic = tic();
                Thread.sleep(100);
                logPIScanTiming("syncReadoutGateSettleDelay", settleTic);

            } else if (globalResetMode) {
                /*
                 * Global-reset mode:
                 * Preserve existing behaviour. Camera exposure output gates
                 * illumination while PI provides one useful trigger per plane.
                 */
                long blankingTic = tic();
                core_.setProperty(DAQDOPort, "Blanking", "On");
                logPIScanTiming("globalResetBlankingOn", blankingTic);
            }

            long speedTic = tic();
            core_.setProperty(mirrorStage, "Velocity", scanSpeed);
            core_.waitForDevice(mirrorStage);
            logPIScanTiming("setScanSpeed", speedTic);

            runnableLogger.info(String.format(
                    "Starting PI mirror scan | mode=%s | currentView=%d | viewCenter=%.5f um | start=%.5f um | triggerStart=%.5f um | triggerEnd=%.5f um | end=%.5f um | frames=%d | triggerEvents=%d | speed=%.6f mm/s",
                    deviceSettings.getTriggerModeLabel(),
                    currentAcq != null ? currentAcq.getCurrentView() : -1,
                    viewMirrorCenterUm,
                    scanStartUm,
                    triggerScanStartUm,
                    triggerScanEndUm,
                    scanEndUm,
                    nFrames,
                    requiredTriggerEvents,
                    scanSpeed));

            long moveScanTic = tic();
            core_.setPosition(mirrorStage, scanEndUm);
            logPIScanTiming("startScanMove", moveScanTic);

            long acquireTic = tic();
            acquireTriggeredDataset(store, nFrames, 15000);
            logPIScanTiming("acquireTriggeredDataset", acquireTic);

            long waitStageDoneTic = tic();
            core_.waitForDevice(mirrorStage);
            logPIScanTiming("waitForMirrorAfterAcquisition", waitStageDoneTic);

        } catch (TimeoutException e) {
            try {
                core_.setSerialPortCommand(mirrorStagePort, "POS? 1", "\n");
                runnableLogger.info(
                        "PI mirror position after timeout: "
                        + core_.getSerialPortAnswer(mirrorStagePort, "\n"));
            } catch (Exception ignored) {
            }

            throw e;

        } catch (Exception e) {
            /*
             * If acquisition fails, invalidate cached trigger geometry. The next
             * scan should fully reconfigure PI trigger settings rather than
             * trusting controller state.
             */
            invalidatePITriggerCache();

            throw new Exception(
                    "PI mirror triggered acquisition failed: "
                    + e.getMessage());

        } finally {
            // -----------------------------------------------------------------
            // Per-view cleanup
            // -----------------------------------------------------------------

            long finallyTic = tic();

            try {
                long stopSeqTic = tic();

                if (core_.isSequenceRunning(camName)) {
                    core_.stopSequenceAcquisition(camName);
                }

                logPIScanTiming("finallyStopSequence", stopSeqTic);

            } catch (Exception e) {
                runnableLogger.warning(
                        "Issue stopping camera sequence in PI cleanup: "
                        + e.getMessage());
            }

            try {
                long trigOffTic = tic();
                PIStage.setPITriggerEnable(mirrorStagePort, PIDeviceID, 0);
                logPIScanTiming("setPITriggerEnableOff", trigOffTic);

            } catch (Exception e) {
                invalidatePITriggerCache();

                runnableLogger.warning(
                        "Failed to disable PI triggering: "
                        + e.getMessage());
            }

            try {
                long dioLowTic = tic();
                PIStage.setPIDigitalOut(mirrorStagePort, PIDeviceID, 0);
                logPIScanTiming("setPIDigitalOutLow", dioLowTic);

            } catch (Exception e) {
                invalidatePITriggerCache();

                runnableLogger.warning(
                        "Failed to set PI digital output low: "
                        + e.getMessage());
            }

            try {
                long blankingTic = tic();
                core_.setProperty(DAQDOPort, "Blank on", "Low");
                core_.setProperty(DAQDOPort, "Blanking", "On");
                logPIScanTiming("restoreBlankingOn", blankingTic);

            } catch (Exception e) {
                runnableLogger.warning(
                        "Failed to restore laser blanking in PI cleanup: "
                        + e.getMessage());
            }

            try {
                long travelSpeedTic = tic();
                core_.setProperty(
                        mirrorStage,
                        "Velocity",
                        deviceSettings.getMirrorStageTravelSpeed());
                logPIScanTiming("restoreMirrorTravelSpeed", travelSpeedTic);

            } catch (Exception e) {
                runnableLogger.warning(
                        "Failed to restore PI mirror travel speed: "
                        + e.getMessage());
            }

            try {
                if (store.getNumImages() != 0) {
                    long freezeTic = tic();
                    store.freeze();
                    logPIScanTiming("storeFreeze", freezeTic);

                    if (frame_.isSaveImgToDisk()) {
                        long closeTic = tic();
                        store.close();
                        logPIScanTiming("storeClose", closeTic);
                    }

                } else {
                    runnableLogger.severe("PI mirror datastore empty");

                    if (frame_.isSaveImgToDisk()) {
                        store.close();
                    }
                }

            } catch (IOException eio) {
                runnableLogger.severe(
                        "Could not freeze/close PI mirror datastore: "
                        + eio.getMessage());
            }

            logPIScanTiming("finallyTotal", finallyTic);
        }

        logPIScanTiming("runSingleViewTotal", runSingleViewTic);
    }
}