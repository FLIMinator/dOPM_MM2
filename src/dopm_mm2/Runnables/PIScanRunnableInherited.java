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
 * <p>This class supplies only the PI-specific single-view scan behaviour.
 * The parent class, {@code AbstractAcquisitionRunnable}, owns:
 * <ul>
 *   <li>camera trigger-mode setup</li>
 *   <li>view 1 / view 2 switching</li>
 *   <li>first-channel storage of stage start positions</li>
 *   <li>last-channel reset of stage positions</li>
 *   <li>final camera trigger cleanup</li>
 * </ul>
 *
 * <p>Current supported PI trigger modes:
 * <ul>
 *   <li>Mode 0: external global reset</li>
 *   <li>Mode 1: external synchronous readout</li>
 * </ul>
 *
 * <p>Mode 2, start trigger + internal camera clock, is intentionally rejected
 * for now. The current PI trigger implementation emits repeated position
 * triggers. Mode 2 requires one clean start trigger followed by an internally
 * clocked camera burst.
 *
 * @author OPMuser
 */
public class PIScanRunnableInherited extends AbstractAcquisitionRunnable {

    private final int PIDeviceID;

    /** Fixed mirror travel margin before the trigger range begins. */
    private static final double SCAN_UNDERSHOOT_UM = 10.0;

    /** Fixed mirror travel margin after the trigger range ends. */
    private static final double SCAN_OVERSHOOT_UM = 10.0;

    public PIScanRunnableInherited(dOPM_hostframe frame_ref,
            MDAProgressManager acqProgressMgr) {
        super(frame_ref, acqProgressMgr);
        PIDeviceID = 1;
    }

    /**
     * PI-specific timing log helper.
     *
     * @param label timing label
     * @param ticMs start time from {@code tic()}
     */
    private void logPIScanTiming(String label, long ticMs) {
        runnableLogger.info(String.format(
                "TIMING | PIScan | %s | %d ms",
                label,
                System.currentTimeMillis() - ticMs));
    }

    /**
     * Acquire one dOPM view using PI mirror scanning.
     *
     * <p>The parent class calls this once for each enabled view.
     *
     * @param opmAngle current OPM view angle
     * @throws Exception if acquisition, triggering, or saving fails
     */
    @Override
    public void runSingleView(double opmAngle) throws Exception {

        long runSingleViewTic = tic();

        // ---------------------------------------------------------------------
        // Validate trigger mode
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

        DisplayWindow display = null;  // Only used if saving to RAM/display.

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

        runnableLogger.info(String.format(
                "PI mirror scan | mode=%s | targetLength=%.3f um | triggerDistance=%.5f um | scanSpeed=%.6f mm/s | startingMirror=%.5f um | viewAngle=%.3f",
                deviceSettings.getTriggerModeLabel(),
                scanLengthUm,
                triggerDistanceUm,
                scanSpeed,
                startingMirrorPositionUm,
                opmAngle));

        // ---------------------------------------------------------------------
        // Derive scan geometry
        // ---------------------------------------------------------------------

        /*
         * The parent class stores startingMirrorPositionUm only on the first
         * channel at a given MDA point. It resets to this position only after
         * the last channel.
         *
         * That means intermediate channels do not return to the scan centre.
         * They still move back to scanStartUm before each scan.
         */
        final double triggerScanStartUm =
                startingMirrorPositionUm - scanLengthUm / 2.0;

        final double targetTriggerScanEndUm =
                startingMirrorPositionUm + scanLengthUm / 2.0;

        /*
         * Keep the existing PI behaviour: actual trigger endpoint is rounded down
         * so the triggered scan contains an integer number of trigger intervals.
         */
        final double triggerScanEndUm =
                triggerScanStartUm
                + triggerDistanceUm * Math.floor(
                        (targetTriggerScanEndUm - triggerScanStartUm)
                        / triggerDistanceUm);

        final double acquiredScanLengthUm =
                triggerScanEndUm - triggerScanStartUm;

        final int nFrames =
                Math.max(1, (int) Math.floor(
                        acquiredScanLengthUm / triggerDistanceUm));

        final double scanStartUm =
                triggerScanStartUm - SCAN_UNDERSHOOT_UM;

        final double scanEndUm =
                triggerScanEndUm + SCAN_OVERSHOOT_UM;

        final double triggerScanStartMillim = triggerScanStartUm * 1e-3;
        final double triggerScanEndMillim = triggerScanEndUm * 1e-3;

        final double effectiveFPS =
                1.0 / (triggerDistanceMillim / scanSpeed);

        runnableLogger.info(String.format(
                "PI mirror geometry | triggerStart=%.5f um | targetTriggerEnd=%.5f um | actualTriggerEnd=%.5f um | acquiredLength=%.5f um | scanStart=%.5f um | scanEnd=%.5f um | nFrames=%d | effectiveFPS=%.3f",
                triggerScanStartUm,
                targetTriggerScanEndUm,
                triggerScanEndUm,
                acquiredScanLengthUm,
                scanStartUm,
                scanEndUm,
                nFrames,
                effectiveFPS));

        // ---------------------------------------------------------------------
        // Configure PI trigger output
        // ---------------------------------------------------------------------

        long triggerSetupTic = tic();

        try {
            /*
             * Basic PI trigger setup:
             * - clear stale serial replies
             * - disable trigger
             * - digital output low
             * - trigger axis = 1
             * - trigger mode = position/distance trigger
             */
            PIStage.setupPITriggering(mirrorStagePort, PIDeviceID);

            /*
             * STP is retained from the original working PI implementation.
             * It appears to help the PI controller accept trigger configuration
             * reliably before a scan.
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

        } catch (Exception e) {
            runnableLogger.severe(
                    "Failed during PI trigger setup: " + e.getMessage());
            throw e;
        }

        logPIScanTiming("triggerSetupTotal", triggerSetupTic);

        try {
            String[] piSettings = PIStage.viewTriggerSettings(mirrorStagePort);
            runnableLogger.info("PI trigger settings after setup: "
                    + String.join(" | ", piSettings));
        } catch (Exception e) {
            runnableLogger.warning(
                    "Could not log PI trigger settings after setup: "
                    + e.getMessage());
        }

        // ---------------------------------------------------------------------
        // Move mirror to scan start
        // ---------------------------------------------------------------------

        long moveToStartTic = tic();

        try {
            /*
             * Move to the undershoot position at travel speed, not scan speed.
             * The parent class does not reset to centre between channels; this
             * move simply returns the mirror to the start side of the scan.
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
                        .putDouble("trigger distance um", triggerDistanceUm)
                        .putDouble("trigger distance mm", triggerDistanceMillim)
                        .putDouble("scan length um", acquiredScanLengthUm)
                        .putDouble("scan start um", scanStartUm)
                        .putDouble("scan end um", scanEndUm)
                        .putDouble("trigger scan start um", triggerScanStartUm)
                        .putDouble("trigger scan end um", triggerScanEndUm)
                        .putDouble("target trigger scan end um", targetTriggerScanEndUm)
                        .putDouble("scan speed mm/s", scanSpeed)
                        .putInteger("nFrames", nFrames)
                        .build();

                /*
                 * d' in the literature: plane spacing in mirror-normal
                 * coordinates, derived from the lateral mirror scan trigger
                 * distance.
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

        /*
         * Defensive wait until the camera reports that sequence acquisition is
         * running. This mirrors the Tango scan path.
         */
        long seqReadyTic = tic();
        int readyCheck = 0;

        while (!core_.isSequenceRunning(camName) && readyCheck < 500) {
            Thread.sleep(10);
            readyCheck++;
        }

        logPIScanTiming("waitForSequenceRunning", seqReadyTic);

        // ---------------------------------------------------------------------
        // Execute scan and collect triggered frames
        // ---------------------------------------------------------------------

        try {
            long trigEnableTic = tic();
            PIStage.setPITriggerEnable(mirrorStagePort, PIDeviceID, 1);
            logPIScanTiming("setPITriggerEnableOn", trigEnableTic);

            if (syncReadoutMode) {
                /*
                 * Match the Tango sync-readout pathway:
                 * - parent setupCameraTriggering() leaves camera output low
                 * - here we explicitly unblank before the scan window
                 * - finally block restores blanking afterwards
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
                 * Preserve the original global-reset behaviour. In this mode,
                 * the camera exposure output can be used as the gating signal,
                 * so blanking is enabled before the triggered sequence.
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
                    "Starting PI mirror scan | mode=%s | start=%.5f um | triggerStart=%.5f um | triggerEnd=%.5f um | end=%.5f um | frames=%d | speed=%.6f mm/s",
                    deviceSettings.getTriggerModeLabel(),
                    scanStartUm,
                    triggerScanStartUm,
                    triggerScanEndUm,
                    scanEndUm,
                    nFrames,
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
            /*
             * If frame acquisition times out, query the actual mirror position.
             * This helps distinguish camera-trigger failure from stage-motion
             * failure.
             */
            try {
                core_.setSerialPortCommand(mirrorStagePort, "POS? 1", "\n");
                runnableLogger.info(
                        "PI mirror position after timeout: "
                        + core_.getSerialPortAnswer(mirrorStagePort, "\n"));
            } catch (Exception ignored) {
            }

            throw e;

        } catch (Exception e) {
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
                runnableLogger.warning(
                        "Failed to disable PI triggering: "
                        + e.getMessage());
            }

            try {
                long dioLowTic = tic();
                PIStage.setPIDigitalOut(mirrorStagePort, PIDeviceID, 0);
                logPIScanTiming("setPIDigitalOutLow", dioLowTic);

            } catch (Exception e) {
                runnableLogger.warning(
                        "Failed to set PI digital output low: "
                        + e.getMessage());
            }

            try {
                /*
                 * Return laser blanking to the safe state after every view.
                 * The parent cleanup also calls switchOffLasers(), so this is
                 * deliberately redundant for safety.
                 */
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