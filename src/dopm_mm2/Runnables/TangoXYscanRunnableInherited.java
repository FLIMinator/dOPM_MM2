/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Runnables;

import dopm_mm2.GUI.dOPM_hostframe;
import dopm_mm2.Devices.TangoXYStage;
import dopm_mm2.acquisition.MDAProgressManager;
import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.data.Datastore;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.display.DisplayWindow;

/** Runnable for Tango XY stage scanning acquisition.
 * @author lnr19
 */
public class TangoXYscanRunnableInherited extends AbstractAcquisitionRunnable{
    public TangoXYscanRunnableInherited(dOPM_hostframe frame_ref, 
            MDAProgressManager acqProgressMgr){
        super(frame_ref, acqProgressMgr);
        try {
            TangoXYStage.setTangoXyUnitsToUm(XYStagePort);
        } catch (Exception e){
            logErrorWithWindow(e);
        }
    }
    @Override
    public void runSingleView(double opmAngle) throws Exception{
        // First, get scan parameters
        double scanLengthXyUm = deviceSettings.getXyStageScanLength();
        String scanAxis = deviceSettings.getXyStageScanAxis();
        double triggerDistanceUm = deviceSettings.getXyStageTriggerDistance();
        double travelSpeed = deviceSettings.getXyStageTravelSpeed();
        double finalScanSpeed;
        int nFrames = (int)(scanLengthXyUm / triggerDistanceUm);

        // SYNC MATH 5.0: PHYSICAL FREE RUN LIMIT (Manual Section 11.1.4)
        double readoutMs = deviceSettings.getCameraReadoutTime(); 
        double exposureMs = core_.getExposure();
        // PHYSICAL LIMIT = max(Readout, Exposure)
        double cameraCycleMs = Math.max(readoutMs, exposureMs); 
        double cameraFps = 1000.0 / cameraCycleMs;

        if (deviceSettings.getTriggerMode() == 2) {
            // UNIT FIX: Master Pulse Interval is in SECONDS
            core_.setProperty(camName, "MASTER PULSE INTERVAL", String.format("%.6f", cameraCycleMs/1000.0));
            core_.setProperty(camName, "MASTER PULSE BURST TIMES", nFrames);
            
            // mm/s = (Slice Thickness um * Camera FPS) / 1000
            finalScanSpeed = (triggerDistanceUm * cameraFps) / 1000.0;
            runnableLogger.info(String.format("MASTER PULSE SYNC: Speed set to %.4f mm/s for physical limit of %.2f FPS", 
                    finalScanSpeed, cameraFps));
        } else {
            finalScanSpeed = deviceSettings.getXyStageCurrentScanSpeed();
        }

        // SCOPE FIX: Calculate coordinates BEFORE they are used in the setup block
        double startingScanPosition;
        switch (scanAxis){
            case "x": startingScanPosition = startingXPositionUm; break;
            case "y": startingScanPosition = startingYPositionUm; break;
            default: throw new Exception("scanAxis error");
        }  
        double triggerScanStartUm = startingScanPosition - scanLengthXyUm/2;
        double targetTriggerScanEndUm = startingScanPosition + scanLengthXyUm/2;
        double scanStartUm = triggerScanStartUm - 15;
        double scanEndUm = targetTriggerScanEndUm + 15;
        
        // Restore Literal Unused Variables for repo fidelity
        double[] triggerRangeUm = new double[]{triggerScanStartUm, targetTriggerScanEndUm};
        double[] triggerRangeMillim = new double[]{triggerScanStartUm*1e-3, targetTriggerScanEndUm*1e-3};

        runnableLogger.info(String.format("%s stage scan target length: %.1f; trigger dist: %.1f um; speed %.4f mm/s; ",
                scanAxis, scanLengthXyUm, triggerDistanceUm, finalScanSpeed));
        
        long start_ = System.currentTimeMillis();
        
        // BEANSHELL HANDSHAKE - ARMED FOR TRIGGERING
        try {
            String axisNum = (scanAxis.equals("x") ? "1" : "2");
            core_.setSerialPortCommand(XYStagePort, "!trigaxis " + axisNum, "\r");
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 
            
            core_.setSerialPortCommand(XYStagePort, "!trigm 4", "\r"); // DISTANCE TRIGGERING
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 

            if (deviceSettings.getTriggerMode() == 2) {
                // STARTER PISTOL: Fire exactly ONE trigger at the start line
                core_.setSerialPortCommand(XYStagePort, String.format("!trigr %.2f %.2f 1", triggerScanStartUm, targetTriggerScanEndUm), "\r");
            } else {
                // STANDARD: Fire trigger every N um
                core_.setSerialPortCommand(XYStagePort, String.format("!trigr %.2f %.2f %d", triggerScanStartUm, targetTriggerScanEndUm, nFrames), "\r");
            }
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 
        } catch (Exception e){ throw new Exception("Tango setup failed: " + e.getMessage()); }
        runnableLogger.info(String.format("trigger setup time %.0f ms", (double)(System.currentTimeMillis()-start_)));

        // PHYSICAL TRAVEL
        try {
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, travelSpeed);
            TangoXYStage.setAxisPosition(XYStage, scanStartUm, scanAxis);
            while(core_.deviceBusy(XYStage)) { Thread.sleep(10); }
        } catch (Exception e){ throw new Exception("Move failed: " + e.toString()); }
        
        // CAMERA PREP
        core_.prepareSequenceAcquisition(camName);
        core_.startSequenceAcquisition(camName, nFrames, 0, true);
        int readyCheck = 0;
        while(!core_.isSequenceRunning(camName) && readyCheck < 100) { Thread.sleep(10); readyCheck++; }
        Thread.sleep(200); 

        // STATIONARY DATASTORE
        Datastore store = createDatastore(mm_.data().summaryMetadataBuilder().zStepUm(triggerDistanceUm).build(), 
                                        PropertyMaps.builder().putString("mode", "MasterPulseStart").build());
        
        // TRIGGER ENABLE
        try {
            core_.setSerialPortCommand(XYStagePort, "!trig 1", "\r");
            core_.readFromSerialPort(XYStagePort);
            core_.setProperty(DAQDOPort, "Blanking", "On");
        } catch (Exception e){ throw new Exception("Trig fail"); }

        // IMAGING SWEEP (Uses Physical Cycle Sync Speed)
        try {
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, finalScanSpeed);
            TangoXYStage.setAxisPosition(XYStage, scanEndUm, scanAxis);
            // Pulse 1 kicks off camera internal sequencer; subsequent pulses ignored by !trigr 1.
            acquireTriggeredDataset(store, nFrames, 15000);
            while(core_.deviceBusy(XYStage)) { Thread.sleep(10); }
        } finally {
            Thread.sleep(150); // Buffer readout grace
            core_.stopSequenceAcquisition(camName);
            core_.setSerialPortCommand(XYStagePort, "!trig 0", "\r");
            core_.readFromSerialPort(XYStagePort);
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, travelSpeed);
            if (store.getNumImages() != 0){ store.freeze(); if(frame_.isSaveImgToDisk()) store.close(); }
        }
        runnableLogger.info(String.format("channel finish setup %.0f ms", (double)(System.currentTimeMillis() - start_)));
    }
}