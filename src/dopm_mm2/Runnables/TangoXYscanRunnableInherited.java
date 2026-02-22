/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Runnables;

import dopm_mm2.GUI.dOPM_hostframe;
import dopm_mm2.Devices.TangoXYStage;
import dopm_mm2.acquisition.MDAProgressManager;
import dopm_mm2.util.FileMM;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.data.Datastore;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.display.DisplayWindow;

/** Runnable for Tango XY stage scanning acquisition.
 * Note that all units are in um here because default precision too low to
 * set trigger distance to order 1um, and also the triggering for the OPM 
 * in 712 was also done in micron
 * *
 * @author lnr19
 */
public class TangoXYscanRunnableInherited extends AbstractAcquisitionRunnable{
    public TangoXYscanRunnableInherited(dOPM_hostframe frame_ref, 
            MDAProgressManager acqProgressMgr){
        super(frame_ref, acqProgressMgr);
        
        // init dimenions of tango
        try {
            TangoXYStage.setTangoXyUnitsToUm(XYStagePort);
        } catch (Exception e){
            logErrorWithWindow(e);
        }
    }
    @Override
    public void runSingleView(double opmAngle) throws Exception{
        // First, get scan length
        double scanLengthXyUm = deviceSettings.getXyStageScanLength();
        String scanAxis = deviceSettings.getXyStageScanAxis();
        double triggerDistanceUm = deviceSettings.getXyStageTriggerDistance();
        double scanSpeed = deviceSettings.getXyStageCurrentScanSpeed();
        double travelSpeed = deviceSettings.getXyStageTravelSpeed();

        
        runnableLogger.info(String.format("%s stage scan \n "
                        + "target scan length: %.1f; "
                        + "trigger distance: %.1f um; "
                        + "scan speed %.4f mm/s; "
                        + "",
                scanAxis, scanLengthXyUm, triggerDistanceUm, scanSpeed));
        // 
        // undershoot so it can reach constant speed
        double scanUndershoot = 15;  // um (Increased for settling)
        double scanOvershoot = scanUndershoot; 
        DisplayWindow display;
        long start_;
        
        // RECENT IMPROVEMENT: BeanShell Handshake - Fixed arming
        start_ = System.currentTimeMillis();
        try {
            String axisNum = (scanAxis.equals("x") ? "1" : "2");
            core_.setSerialPortCommand(XYStagePort, "!trigaxis " + axisNum, "\r");
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 
            
            core_.setSerialPortCommand(XYStagePort, "!trigm 4", "\r");
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 

            core_.setSerialPortCommand(XYStagePort, "!trigdist " + triggerDistanceUm, "\r");
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 
        } catch (Exception e){
            throw new Exception("Failed to set tango trigger setup with " + e.getMessage());
        }
        runnableLogger.info(String.format("trigger axis and distance setup time %d ms", 
                System.currentTimeMillis()-start_));

        // work out range so that an integer number of triggers has the correct
        // trigger distance (the tango trigger range is a bit like numpy 
        // linspace)
        double startingScanPosition;
        switch (scanAxis){
            // starting positions are set in Abstract class bit. 
            case "x":
                startingScanPosition = startingXPositionUm;
                break;
            case "y":
                startingScanPosition = startingYPositionUm;
                break;
            default:
                throw new Exception("scanAxis should be x or y");
        }  

        double triggerScanStartUm = startingScanPosition - scanLengthXyUm/2;
        double targetTriggerScanEndUm = startingScanPosition + scanLengthXyUm/2;
        
        double[] triggerRangeUm = new double[]{
            triggerScanStartUm, targetTriggerScanEndUm};
        
        double[] triggerRangeMillim = new double[]{
            triggerScanStartUm*1e-3, targetTriggerScanEndUm*1e-3};
        
        double actualTriggerScanEndUm = targetTriggerScanEndUm;
        
        start_ = System.currentTimeMillis();
        try {
            // Literal restoration of Range Command with Handshake
            String rangeCmd = String.format("!trigr %.2f %.2f %d", 
                triggerScanStartUm, targetTriggerScanEndUm, (int)(scanLengthXyUm/triggerDistanceUm));
            core_.setSerialPortCommand(XYStagePort, rangeCmd, "\r");
            core_.setSerialPortCommand(XYStagePort, "err", "\r");
            core_.getSerialPortAnswer(XYStagePort, "\r"); 
        } catch (Exception e){
            throw new Exception("Failed to set range: " + e.getMessage());
        }
        double actualScanLength = actualTriggerScanEndUm - triggerScanStartUm;
        double scanStartUm = triggerScanStartUm - scanUndershoot;
        double scanEndUm = actualTriggerScanEndUm + scanOvershoot;
        int nFrames = (int)(actualScanLength/triggerDistanceUm);
        
        runnableLogger.info(String.format("Trigger interval set time %d ms", 
                System.currentTimeMillis()-start_));
        
        start_ = System.currentTimeMillis();
        // PHYSICAL MOVE TO START (Now optimized - moves directly between colors)
        try {
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, travelSpeed);
            TangoXYStage.setAxisPosition(XYStage, scanStartUm, scanAxis);
            while(core_.deviceBusy(XYStage)) { Thread.sleep(10); }
        } catch (Exception e){
            throw new Exception("Failed to move to start position: " + e.toString());
        }
        
        // RECENT IMPROVEMENT: stationary prepare camera sequence
        core_.prepareSequenceAcquisition(camName);
        core_.startSequenceAcquisition(camName, nFrames, 0, true);
        
        // Wait for camera hardware acknowledgement
        int readyCheck = 0;
        while(!core_.isSequenceRunning(camName) && readyCheck < 100) {
            Thread.sleep(10); readyCheck++;
        }
        Thread.sleep(200); // Settling

        // Create datastore
        Datastore store;
        if (frame_.isSaveImgToDisk()){
            try {
                PropertyMap myPropertyMap = PropertyMaps.builder().
                    putString("scan type", "stage scanning").
                    putString("scan axis", scanAxis).
                    putDouble("trigger distance um", triggerDistanceUm).
                    putDouble("scan length um", actualScanLength).
                        build();
                SummaryMetadata metadata = mm_.data().summaryMetadataBuilder().
                        zStepUm(triggerDistanceUm).build();
                store = createDatastore(metadata, myPropertyMap);
            } catch (Exception e){
                throw new Exception ("Datastore Fail: " + e.getMessage());
            }
        } else {
            store = mm_.data().createRAMDatastore();
            display = mm_.displays().createDisplay(store);
        }
        
        // ENABLE TTL TRIGGERING
        try {
            core_.setSerialPortCommand(XYStagePort, "!trig 1", "\r");
            core_.readFromSerialPort(XYStagePort);
            core_.setProperty(DAQDOPort, "Blanking", "On");
        } catch (Exception e){
            throw new Exception("Trig enable fail");
        }
        runnableLogger.info(String.format("trigger enable and sequence prep time %d ms", 
                System.currentTimeMillis()-start_));

        // IMAGING SWEEP
        try {
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, scanSpeed);
            TangoXYStage.setAxisPosition(XYStage, scanEndUm, scanAxis);
            acquireTriggeredDataset(store, nFrames);
            while(core_.deviceBusy(XYStage)) { Thread.sleep(10); }
        } finally {
            // STOP AND RESET
            Thread.sleep(150); // Readout drain
            core_.stopSequenceAcquisition(camName);
            core_.setSerialPortCommand(XYStagePort, "!trig 0", "\r");
            core_.readFromSerialPort(XYStagePort);
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, travelSpeed);
            if (store.getNumImages() != 0){
                store.freeze();
                if(frame_.isSaveImgToDisk()) store.close();
            }
        }
        
        runnableLogger.info(String.format("channel finish setup %d ms", 
                System.currentTimeMillis()-start_));
    }
}