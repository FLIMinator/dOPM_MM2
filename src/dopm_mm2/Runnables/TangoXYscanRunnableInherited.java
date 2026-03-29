/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Runnables;

import dopm_mm2.Devices.DeviceSettingsManager;
import dopm_mm2.Devices.TangoXYStage;
import dopm_mm2.GUI.dOPM_hostframe;
import dopm_mm2.acquisition.MDAProgressManager;
import org.micromanager.PropertyMaps;
import org.micromanager.data.Datastore;

/** Runnable for Tango XY stage scanning acquisition.
 *
 * <p>This implementation:
 * <ul>
 *   <li>keeps Tango trigger-range logic inside {@code TangoXYStage}</li>
 *   <li>uses the outer runnable/base class for camera mode setup and view recentering</li>
 *   <li>avoids unnecessary fixed sleeps at the end of each scan</li>
 * </ul>
 *
 * <p>Trigger modes:
 * <ul>
 *   <li>0 = external global reset</li>
 *   <li>1 = external synchronous readout</li>
 *   <li>2 = start trigger + internal master clock burst</li>
 * </ul>
 *
 * <p>In mode 1 only, the camera output is toggled HIGH for the scan duration and
 * LOW again afterwards so the laser is only enabled during the actual scan.
 */
public class TangoXYscanRunnableInherited extends AbstractAcquisitionRunnable {

    /** Mode-2 helper distance.
     *
     * <p>This is deliberately much larger than the normal scan range so the
     * trigger-range helper enters its single-start-trigger special case.
     */
    private static final double INTERNAL_CLOCK_START_ONLY_TRIGGER_DISTANCE_UM = 10000.0;

    /** Fixed travel margin before the trigger range begins. */
    private static final double SCAN_UNDERSHOOT_UM = 15.0;

    /** Fixed travel margin after the trigger range ends. */
    private static final double SCAN_OVERSHOOT_UM = 15.0;

    public TangoXYscanRunnableInherited(dOPM_hostframe frame_ref,
            MDAProgressManager acqProgressMgr) {
        super(frame_ref, acqProgressMgr);
        try {
            TangoXYStage.setTangoXyUnitsToUm(XYStagePort);
        } catch (Exception e) {
            logErrorWithWindow(e);
        }
    }

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------

    private void logScanTiming(String label, long ticMs) {
        runnableLogger.info(String.format(
                "TIMING | TangoScan | %s | %d ms",
                label, System.currentTimeMillis() - ticMs));
    }

    private void logCameraTriggerState(String label) {
        try {
            runnableLogger.info(String.format(
                    "CAMERA STATE | %s | mode=%s | ScanMode=%s | TriggerSource=%s | TriggerActive=%s | TriggerGlobalExposure=%s | MasterPulseMode=%s | MasterPulseTriggerSource=%s | OutputKind=%s",
                    label,
                    deviceSettings.getTriggerModeLabel(),
                    core_.getProperty(camName, "ScanMode"),
                    core_.getProperty(camName, "TRIGGER SOURCE"),
                    core_.getProperty(camName, "TRIGGER ACTIVE"),
                    core_.getProperty(camName, "TRIGGER GLOBAL EXPOSURE"),
                    core_.getProperty(camName, "MASTER PULSE MODE"),
                    core_.getProperty(camName, "MASTER PULSE TRIGGER SOURCE"),
                    core_.getProperty(camName, "OUTPUT TRIGGER KIND[1]")));
        } catch (Exception e) {
            runnableLogger.warning(
                    "Could not log camera trigger state: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Main single-view acquisition
    // -------------------------------------------------------------------------

    @Override
    public void runSingleView(double opmAngle) throws Exception {
        long runSingleViewTic = tic();

        // ---------------------------------------------------------------------
        // Read scan settings and derive geometry
        // ---------------------------------------------------------------------

        final double scanLengthXyUm = deviceSettings.getXyStageScanLength();
        final String scanAxis = deviceSettings.getXyStageScanAxis();
        final double triggerDistanceUm = deviceSettings.getXyStageTriggerDistance();
        final double travelSpeed = deviceSettings.getXyStageTravelSpeed();
        final double exposureMs = core_.getExposure();

        final int triggerMode = deviceSettings.getTriggerMode();
        final boolean internalClockMode =
                triggerMode == DeviceSettingsManager.TRIGGER_START_INTERNAL_CLOCK;
        final boolean syncReadoutMode =
                triggerMode == DeviceSettingsManager.TRIGGER_EXTERNAL_SYNCREADOUT;

        final double startingScanPosition;
        switch (scanAxis) {
            case "x":
                startingScanPosition = startingXPositionUm;
                break;
            case "y":
                startingScanPosition = startingYPositionUm;
                break;
            default:
                throw new Exception(
                        "scanAxis error: expected x or y but found " + scanAxis);
        }

        final double triggerScanStartUm =
                startingScanPosition - scanLengthXyUm / 2.0;
        final double targetTriggerScanEndUm =
                startingScanPosition + scanLengthXyUm / 2.0;
        final double[] desiredTriggerRangeUm =
                new double[]{triggerScanStartUm, targetTriggerScanEndUm};

        final double scanStartUm = triggerScanStartUm - SCAN_UNDERSHOOT_UM;
        final double scanEndUm = targetTriggerScanEndUm + SCAN_OVERSHOOT_UM;

        final int nFrames = Math.max(
                1, (int) Math.floor(scanLengthXyUm / triggerDistanceUm));

        // ---------------------------------------------------------------------
        // Camera scan mode and timing
        // ---------------------------------------------------------------------

        // All timing calculations below assume Hamamatsu ScanMode 3.
        long scanModeTic = tic();
        core_.setProperty(camName, "ScanMode", "3");
        logScanTiming("setScanMode3", scanModeTic);

        long timingCalcTic = tic();
        final double cameraCycleMs =
                deviceSettings.getCameraReadoutTime(exposureMs);
        logScanTiming("getCameraReadoutTime", timingCalcTic);

        final double cameraFps = 1000.0 / cameraCycleMs;

        final double finalScanSpeed;
        if (internalClockMode) {
            finalScanSpeed = (triggerDistanceUm * cameraFps) / 1000.0;
            core_.setProperty(camName, "MASTER PULSE INTERVAL",
                    String.format("%.6f", cameraCycleMs / 1000.0));
            core_.setProperty(camName, "MASTER PULSE BURST TIMES", nFrames);
		} else {
            finalScanSpeed = deviceSettings.getXyStageCurrentScanSpeed();
        }

        runnableLogger.info(String.format(
                "Tango %s-scan | triggerMode=%s | targetLength=%.1f um | triggerDist=%.3f um | nFrames=%d | cameraCycle=%.4f ms | finalSpeed=%.4f mm/s",
                scanAxis,
                deviceSettings.getTriggerModeLabel(),
                scanLengthXyUm,
                triggerDistanceUm,
                nFrames,
                cameraCycleMs,
                finalScanSpeed));

        // ---------------------------------------------------------------------
        // Tango trigger setup
        // ---------------------------------------------------------------------

        long triggerSetupTic = tic();
        try {
            TangoXYStage.setTangoTriggerAxis(XYStagePort, scanAxis);

            if (internalClockMode) {
                /*
                 * Mode 2 uses the same trigger-range API surface as modes 0/1,
                 * but with a deliberately huge trigger distance so the helper
                 * falls into its single-start-trigger special case.
                 *
                 * Because the stage approaches from an underrun region, the
                 * trigger occurs as the scan enters the imaging interval and
                 * acts as the starter pulse for the camera's internal burst.
                 */
                TangoXYStage.setTangoTriggerDistance(
                        XYStagePort,
                        scanAxis,
                        INTERNAL_CLOCK_START_ONLY_TRIGGER_DISTANCE_UM);
                TangoXYStage.setTangoTriggerRange(
                        XYStagePort,
                        scanAxis,
                        desiredTriggerRangeUm,
                        INTERNAL_CLOCK_START_ONLY_TRIGGER_DISTANCE_UM);
            } else {
                TangoXYStage.setTangoTriggerDistance(
                        XYStagePort, scanAxis, triggerDistanceUm);
                TangoXYStage.setTangoTriggerRange(
                        XYStagePort, scanAxis, desiredTriggerRangeUm, triggerDistanceUm);
            }
        } catch (Exception e) {
            throw new Exception(
                    "Tango trigger setup failed: " + e.getMessage());
        }

        logScanTiming("triggerSetupTotal", triggerSetupTic);
        logCameraTriggerState("afterTriggerSetup");

        // ---------------------------------------------------------------------
        // Move stage to scan start position
        // ---------------------------------------------------------------------

        long moveToStartTic = tic();
        try {
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, travelSpeed);
            TangoXYStage.setAxisPosition(XYStage, scanStartUm, scanAxis);

            long waitStartBusyTic = tic();
            while (core_.deviceBusy(XYStage)) {
                Thread.sleep(10);
            }
            logScanTiming("waitForMoveToScanStart", waitStartBusyTic);
            logScanTiming("moveToScanStartTotal", moveToStartTic);
        } catch (Exception e) {
            throw new Exception(
                    "Failed to move Tango stage to scan start: " + e.getMessage());
        }

        // ---------------------------------------------------------------------
        // Arm camera sequence
        // ---------------------------------------------------------------------

        long seqStartTic = tic();
        core_.prepareSequenceAcquisition(camName);
        logScanTiming("prepareSequenceAcquisition", seqStartTic);

        long seqRunTic = tic();
        core_.startSequenceAcquisition(camName, nFrames, 0, true);
        logScanTiming("startSequenceAcquisition", seqRunTic);

        long seqReadyTic = tic();
        int readyCheck = 0;
        while (!core_.isSequenceRunning(camName) && readyCheck < 500) {
            Thread.sleep(10);
            readyCheck++;
        }
        logScanTiming("waitForSequenceRunning", seqReadyTic);
        logCameraTriggerState("afterSequenceArmed");

        // ---------------------------------------------------------------------
        // Datastore metadata
        // ---------------------------------------------------------------------

        final String acqModeLabel = internalClockMode
                ? "StartTriggerInternalClock"
                : (syncReadoutMode
                    ? "ExternalSyncReadout"
                    : "ExternalGlobalReset");

        long datastoreTic = tic();
        Datastore store = createDatastore(
                mm_.data().summaryMetadataBuilder()
                        .zStepUm(triggerDistanceUm)
                        .build(),
                PropertyMaps.builder()
                        .putString("mode", acqModeLabel)
                        .build());
        logScanTiming("createDatastore", datastoreTic);

        // ---------------------------------------------------------------------
        // Execute scan and collect triggered dataset
        // ---------------------------------------------------------------------

        try {
            long enableTrigTic = tic();
            TangoXYStage.setTangoTriggerEnable(XYStagePort, 1);
            logScanTiming("setTangoTriggerEnableOn", enableTrigTic);

            if (syncReadoutMode) {
				    long blankingOnTic = tic();
					core_.setProperty(DAQDOPort, "Blank on", "Low");
					core_.setProperty(DAQDOPort, "Blanking", "Off");
					logScanTiming("blankingOff", blankingOnTic);
                    long gateSettleTic = tic();
                    Thread.sleep(100);
                    logScanTiming("syncReadoutGateSettleDelay", gateSettleTic);
                    logCameraTriggerState("afterSyncReadoutGateSettleDelay");
            }

            long setScanSpeedTic = tic();
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, finalScanSpeed);
            logScanTiming("setScanSpeed", setScanSpeedTic);

            long moveScanTic = tic();
            TangoXYStage.setAxisPosition(XYStage, scanEndUm, scanAxis);
            logScanTiming("startScanMove", moveScanTic);

            if (!internalClockMode) {
                runnableLogger.info(String.format(
                        "SYNC CHECK | external-triggered scan armed | mode=%s | trigger every %.3f um over %.1f-%.1f um",
                        deviceSettings.getTriggerModeLabel(),
                        triggerDistanceUm,
                        triggerScanStartUm,
                        targetTriggerScanEndUm));
            } else {
                runnableLogger.info(String.format(
                        "STARTER CHECK | internal-clock burst armed | startTrigger=%.2f um | hugeTriggerDistance=%.2f um | burstFrames=%d | masterPulseInterval=%.6f s",
                        triggerScanStartUm,
                        INTERNAL_CLOCK_START_ONLY_TRIGGER_DISTANCE_UM,
                        nFrames,
                        cameraCycleMs / 1000.0));
            }

            long acquireTic = tic();
            acquireTriggeredDataset(store, nFrames, 15000);
            logScanTiming("acquireTriggeredDataset", acquireTic);

            long waitStageDoneTic = tic();
            while (core_.deviceBusy(XYStage)) {
                Thread.sleep(10);
            }
            logScanTiming("waitForStageAfterAcquisition", waitStageDoneTic);
        } finally {
            // -----------------------------------------------------------------
            // Cleanup
            // -----------------------------------------------------------------

            long finallyTic = tic();

            try {
                long stopSeqTic = tic();
                if (core_.isSequenceRunning(camName)) {
                    core_.stopSequenceAcquisition(camName);
                }
                logScanTiming("finallyStopSequence", stopSeqTic);
            } catch (Exception e) {
                runnableLogger.warning(
                        "Issue stopping sequence acquisition in Tango cleanup: "
                        + e.getMessage());
            }

            try {
                long trigOffTic = tic();
                TangoXYStage.setTangoTriggerEnable(XYStagePort, 0);
                logScanTiming("setTangoTriggerEnableOff", trigOffTic);
            } catch (Exception ignore) {}

            try {
                long blankingOffTic = tic();
				core_.setProperty(DAQDOPort, "Blank on", "Low");
				core_.setProperty(DAQDOPort, "Blanking", "On");
                logScanTiming("blankingOn", blankingOffTic);
            } catch (Exception ignore) {}

            try {
                long travelSpeedTic = tic();
                TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, travelSpeed);
                logScanTiming("restoreTravelSpeed", travelSpeedTic);
            } catch (Exception ignore) {}

            if (store.getNumImages() != 0) {
                long freezeTic = tic();
                store.freeze();
                logScanTiming("storeFreeze", freezeTic);

                if (frame_.isSaveImgToDisk()) {
                    long closeTic = tic();
                    store.close();
                    logScanTiming("storeClose", closeTic);
                }
            }

            logScanTiming("finallyTotal", finallyTic);
        }

        logScanTiming("runSingleViewTotal", runSingleViewTic);
    }
}