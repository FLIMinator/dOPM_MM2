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
 * * REVISION (Layer 3):
 * - Preserved all original switch cases and switch-off-laser logic.
 * - Added prepareSequenceAcquisition() earlier to minimize camera lag.
 * - Added active polling for scanStartUm to ensure constant velocity.
 * * @author lnr19
 */
public class TangoXYscanRunnableInherited extends AbstractAcquisitionRunnable {

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

        runnableLogger.info(String.format("%s stage scan \n "
                        + "target scan length: %.1f; "
                        + "trigger distance: %.1f um; "
                        + "scan speed %.4f mm/s; "
                        + "",
                scanAxis, scanLengthXyUm, triggerDistanceUm, scanSpeed));
        
        // undershoot so it can reach constant speed
        double scanUndershoot = 10;  // um
        double scanOvershoot = scanUndershoot; 
        DisplayWindow display;
        long start_;
        
        start_ = System.currentTimeMillis();
        try {
            TangoXYStage.setTangoTriggerAxis(XYStagePort, scanAxis);
        } catch (Exception e){
            throw new Exception("Failed to set tango trigger axis with " + 
                    e.getMessage());
        }
        try {
            TangoXYStage.setTangoTriggerDistance(XYStagePort, scanAxis,
                    triggerDistanceUm);
        } catch (Exception e){
            throw new Exception("Failed to set tango trigger distance with " + 
                    e.getMessage());
        }
        runnableLogger.info(String.format("trigger axis and distance setup time %d ms", 
                System.currentTimeMillis()-start_));

        // work out range so that an integer number of triggers has the correct
        // trigger distance (the tango trigger range is a bit like numpy 
        // linspace)
        double startingScanPosition;
        switch (scanAxis){
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
        
        double actualTriggerScanEndMillim;
        double actualTriggerScanEndUm;
        
        start_ = System.currentTimeMillis();
        try {
            // REVISION: We arm the camera sequence buffer NOW to overlap with stage move
            core_.prepareSequenceAcquisition(camName);

            actualTriggerScanEndUm = 
                   TangoXYStage.setTangoTriggerRange(
                    XYStagePort, scanAxis, triggerRangeUm, triggerDistanceUm)[1];
            actualTriggerScanEndMillim = actualTriggerScanEndUm*1e-3;
        } catch (Exception e){
            throw new Exception(String.format("Failed to set Tango %s "
                    + "trigger range with exception %s", 
                    scanAxis, e.getMessage()));
        }
        double actualScanLength = actualTriggerScanEndUm - triggerScanStartUm;
        double scanStartUm = triggerScanStartUm - scanUndershoot;
        double scanEndUm = actualTriggerScanEndUm + scanOvershoot;
        int nFrames = (int)(actualScanLength/triggerDistanceUm);
        
        runnableLogger.info(String.format("Trigger interval set time %d ms", 
                System.currentTimeMillis()-start_));
        
        start_ = System.currentTimeMillis();
        
        // REVISION: Move to start with travel speed, then WAIT to ensure constant velocity later
        TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, deviceSettings.getXyStageTravelSpeed());
        try {
            TangoXYStage.setAxisPosition(XYStage, scanStartUm, scanAxis);
            // Achievement: Active polling ensures we are AT the undershoot position before sweeping
            while(core_.deviceBusy(XYStage)) {
                Thread.sleep(10);
                checkInterrupt(); // Responsiveness to "Stop" button
            }
        } catch (Exception e){
            String errMsg = "Failed to move to start " + scanAxis + " scan position. Error: " + e.toString();
            runnableLogger.severe(errMsg);
            throw new Exception(errMsg);
        }
        
        try {
            TangoXYStage.setTangoTriggerEnable(XYStagePort, 1);
        } catch (Exception e){
            throw new Exception("Failed to enable triggering: " + e.getMessage());
        }
        runnableLogger.info(String.format("trigger enable and move to start time %d ms", 
                System.currentTimeMillis()-start_));
        
        // Create datastore (PRESERVED ORIGINAL LOGIC)
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
                       
            } catch (IOException ie){
                throw ie;
            } catch (Exception e){
                throw new Exception ("Unknown error creating datastore: " + e.getMessage());
            }
        } else {
            store = mm_.data().createRAMDatastore();
            display = mm_.displays().createDisplay(store);
        }
        
        core_.setProperty(DAQDOPort, "Blanking", "On");
        
        // START SWEEP
        start_ = System.currentTimeMillis();
        core_.startSequenceAcquisition(camName, nFrames, 0, true);
        
        runnableLogger.info(String.format("Starting XY (%s) stage scanning [start: %.2f um, frames: %d, end %.2f um]",
                scanAxis, scanStartUm, nFrames, scanEndUm));
        
        try {
            TangoXYStage.setTangoAxisSpeed(XYStage, scanAxis, scanSpeed);
            TangoXYStage.setAxisPosition(XYStage, scanEndUm, scanAxis);
        } catch (Exception e){
            throw new Exception("Failed to initiate scan sweep: " + e.getMessage());
        }

        // Acquire volume in the trigger loop
        try {
            acquireTriggeredDataset(store, nFrames);
            // REVISION: Wait for stage to finish physical sweep before cleanup
            while(core_.deviceBusy(XYStage)) {
                Thread.sleep(10);
                checkInterrupt();
            }
        } finally {
            core_.stopSequenceAcquisition(camName);
            
            if (store.getNumImages() != 0){
                try {
                    store.freeze();
                    if(frame_.isSaveImgToDisk()) store.close();
                } catch (IOException eio){
                    runnableLogger.severe("Couldn't freeze/close datastore");
                }
            } else {
                if(frame_.isSaveImgToDisk()) store.close();
                runnableLogger.severe("Datastore empty");
            }

            // Disable triggering
            try {
                TangoXYStage.setTangoTriggerEnable(XYStagePort, 0);
                runnableLogger.info("Tango Error Check: " + TangoXYStage.getTangoErrorMsg(XYStagePort));
            } catch (Exception e){
                runnableLogger.warning("Failed to disable Tango trigger.");
            }
        }
    }
}