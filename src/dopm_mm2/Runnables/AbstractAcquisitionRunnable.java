/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package dopm_mm2.Runnables;

import dopm_mm2.Devices.DeviceSettingsManager;
import dopm_mm2.Devices.PIStage;
import dopm_mm2.Devices.TangoXYStage;
import dopm_mm2.GUI.dOPM_hostframe;
import dopm_mm2.util.FileMM;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import mmcorej.CMMCore;
import mmcorej.TaggedImage;
import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.StagePosition;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.data.Metadata;
import org.micromanager.data.SummaryMetadata;

import dopm_mm2.acquisition.MDAProgressManager;
import dopm_mm2.util.dialogBoxes;

/** Abstract class for dOPM runnables, switching between views and calling
 * runSingleView for each view.
 * When writing a new dOPM runnable that extends this abstract class,
 * runSingleView is overloaded and you shouldn't need to do anything
 * with run itself
 * * @author Leo Rowe-Brown
 */
public abstract class AbstractAcquisitionRunnable implements Runnable {
    
    protected final dOPM_hostframe frame_;
    protected final CMMCore core_;
    protected final Studio mm_;
    protected final DeviceSettingsManager deviceSettings;
    protected final MDAProgressManager currentAcq;
    protected double currentViewAngle;
    
    protected String settingsOutDir;
    protected String dataOutDir;
    
    protected boolean errorWindowsDuringAcq;

    protected final String camName;
    protected final String mirrorStage;
    protected final String XYStage;
    protected final String ZStage;
    protected final String filterWheel;
    protected String filter;
    protected String laser;
    protected List<String> lasers;
    protected StagePosition stagePosition;
    protected PositionList positionList;
    
    protected final String XYStagePort;
    protected final String mirrorStagePort;
    protected final String DAQDOPort;
    
    // starting stage positions:
    protected double startingXPositionUm;
    protected double startingYPositionUm;
    protected double startingZpositionUm;
    protected double startingMirrorPositionUm;
    protected double zStepUm;
    
    protected double volumeScanLength;
    
    protected int maxDroppedFrames;
    protected boolean acquisitionFailed;  // sets true if acq gets error
    
    protected long endClockTimeMs;  // for estimating MDA's snap and overhead duration

    
    // Use the "MDA" logger 
    protected static final Logger runnableLogger = 
        Logger.getLogger(MDARunnable.class.getName());
    
    /** Timing helpers for profiling where acquisition overhead accumulates. */
    protected long tic() { return System.currentTimeMillis(); }

    protected void logTiming(String label, long ticMs) {
        runnableLogger.info(String.format("TIMING | %s | %d ms", label,
                System.currentTimeMillis() - ticMs));
    }

    protected void logTiming(String scope, String label, long ticMs) {
        runnableLogger.info(String.format("TIMING | %s | %s | %d ms", scope,
                label, System.currentTimeMillis() - ticMs));
    }

    public AbstractAcquisitionRunnable(dOPM_hostframe frame_ref, 
            MDAProgressManager acqProgressMgr) {
        frame_ = frame_ref;  // consider changing dependency to just deviceSettings
        mm_ = dOPM_hostframe.mm_;
        core_ = mm_.getCMMCore();
        deviceSettings = frame_.getDeviceSettings();
        currentAcq = acqProgressMgr;
        
        errorWindowsDuringAcq = true;
        maxDroppedFrames = 0;
        
        // device variables
        camName = deviceSettings.getdOPMCameraName();
        mirrorStage = deviceSettings.getMirrorStageName();
        XYStage = deviceSettings.getXyStageName();
        ZStage = deviceSettings.getZStageName();
        filterWheel = deviceSettings.getFilterDeviceName();
        DAQDOPort = deviceSettings.getLaserBlankingDOport();
        
        runnableLogger.info(String.format("Variables: camera: %s, mirror stage: %s, xy stage: %s, z stage: %s, filter wheel: %s, DAQ DO port: %s",
                camName, mirrorStage, XYStage, ZStage, filterWheel, DAQDOPort));   
        
        XYStagePort = deviceSettings.getXyStageComPort();
        mirrorStagePort = deviceSettings.getMirrorStageComPort();
        
        endClockTimeMs = 0;
    }
    
    @Override
    public void run(){
        runnableLogger.info("In runnable's run()");
        if (endClockTimeMs != 0){
            runnableLogger.info(String.format(
                    "MDA's snap and overheads added %d ms to acq",
                    System.currentTimeMillis()-endClockTimeMs));
        }
        long waitForSystemTic = tic();
        try {
            // wait until stage has reached position in position list
            core_.waitForSystem();
            logTiming("run", "waitForSystem", waitForSystemTic);
        } catch (Exception e){
            String msg = "Failed to wait for devices before "
                    + "acquisition with " + e.toString();
            runnableLogger.severe(msg);
            logErrorWithWindow(e);
            // Thread.sleep(10000);
        }
        long start = System.currentTimeMillis();           
        
        // Set scan speed variables accordingly for mirror and xystage
        long speedUpdateTic = tic();
        deviceSettings.updateCurrentScanSpeedsDuringAcq();
        logTiming("run", "updateCurrentScanSpeedsDuringAcq", speedUpdateTic);

        final boolean storeStageStartThisRun = shouldStoreStageStartingPositionsThisRun();
        final boolean resetStageAtEndThisRun = shouldResetStagePositionsThisRun();
                
        try {
            // Setup Camera Triggering using provided logic
            long setupCameraTic = tic();
            setupCameraTriggering();
            logTiming("run", "setupCameraTriggering", setupCameraTic);

            long triggerResetTic = tic();
            PIStage.setPITriggerLow(mirrorStagePort);
            TangoXYStage.setTangoTriggerEnable(XYStagePort, 0);
            logTiming("run", "resetStageTriggerLines", triggerResetTic);

            if (storeStageStartThisRun) {
                long storeStartTic = tic();
                storeStageStartingPositions();
                logTiming("run", "storeStageStartingPositions", storeStartTic);
            } else {
                runnableLogger.info(String.format(
                        "Skipping storeStageStartingPositions at channel %d/%d",
                        currentAcq != null ? currentAcq.getCurrentAcqChannelIdx() + 1 : -1,
                        currentAcq != null ? currentAcq.getnChannelPts() : -1));
            }

            runnableLogger.info(String.format("Runnable setup inside run took %d ms", 
                    System.currentTimeMillis()-start));

            // View 1
            if (deviceSettings.isView1Imaged()){
                runnableLogger.info("Acquiring view 1");
                try { 
                    currentAcq.setCurrentView(1);
                } catch (Exception e){
                    runnableLogger.severe("Failed to change to view 1");
                    return;
                }
                currentViewAngle = -deviceSettings.getOpmAngle();
                long view1TotalTic = tic();
                try {
                    long runSingleViewTic = tic();
                    runSingleView(currentViewAngle);
                    logTiming("view1", "runSingleView", runSingleViewTic);
                } catch (Exception e){
                    logErrorWithWindow(e);
                } finally {
                    long cleanupTic = tic();
                    cleanupAcq();
                    logTiming("view1", "cleanupAcq", cleanupTic);
                    logTiming("view1", "total", view1TotalTic);
                }
            }
            
            // View 2
            if (deviceSettings.isView2Imaged()){
                runnableLogger.info("Acquiring view 2");
                try { 
                    currentAcq.setCurrentView(2);
                } catch (Exception e){
                    runnableLogger.severe("Failed to change to view 2");
                }
                currentViewAngle = deviceSettings.getOpmAngle();
                long view2TotalTic = tic();
                try {
                    long runSingleViewTic = tic();
                    runSingleView(currentViewAngle);
                    logTiming("view2", "runSingleView", runSingleViewTic);
                } catch (Exception e){
                    logErrorWithWindow(e);
                } finally {
                    long cleanupTic = tic();
                    cleanupAcq();
                    logTiming("view2", "cleanupAcq", cleanupTic);
                    logTiming("view2", "total", view2TotalTic);
                }
            }
        } catch (Exception hardwareEx) {
            logErrorWithWindow("Hardware setup logic fail: " + hardwareEx.getMessage());
        } finally {
            if (resetStageAtEndThisRun) {
                long resetTic = tic();
                setStagePositionsToStart();
                logTiming("run", "setStagePositionsToStart", resetTic);
            } else {
                runnableLogger.info(String.format(
                        "Skipping setStagePositionsToStart at channel %d/%d",
                        currentAcq != null ? currentAcq.getCurrentAcqChannelIdx() + 1 : -1,
                        currentAcq != null ? currentAcq.getnChannelPts() : -1));
            }

            // VITAL SAFETY: Return camera to internal sync even if acquisition fails or is aborted
            try {
                stopCameraTriggering();
            } catch (Exception e){
                runnableLogger.severe("Critical tidy-up fail: " + e.getMessage());
            }
            if (currentAcq!=null){
                currentAcq.nextAcqPoint();
            }
        }
        
        runnableLogger.info(String.format(
                "Full acquisition took %d ms", 
                System.currentTimeMillis()-start));
        endClockTimeMs = System.currentTimeMillis();
    }
    
    public abstract void runSingleView(double currentViewAngle) throws Exception;

    /**
     * Channel is assumed to vary faster than time and position.
     * Store stage starting positions once at the first channel.
     */
    protected boolean shouldStoreStageStartingPositionsThisRun() {
        if (currentAcq == null) {
            return true;
        }
        return currentAcq.getCurrentAcqChannelIdx() == 0;
    }

    /**
     * Channel is assumed to vary faster than time and position.
     * Reset stage positions once at the last channel.
     */
    protected boolean shouldResetStagePositionsThisRun() {
        if (currentAcq == null) {
            return true;
        }
        return currentAcq.getCurrentAcqChannelIdx()
                == currentAcq.getnChannelPts() - 1;
    }

    
    protected void logErrorWithWindow(Exception e){
        String msg = e.toString();
        logErrorWithWindow(msg);
    }
    
    protected void logErrorWithWindow(String msg){
        runnableLogger.severe(msg);
        mm_.acquisitions().abortAcquisition();
        mm_.acquisitions().isAcquisitionRunning();
        mm_.acquisitions().clearRunnables();
        
        acquisitionFailed = true;
        if (errorWindowsDuringAcq) dialogBoxes.acquisitionErrorWindow(msg);
    }
    
    protected void storeStageStartingPositions() throws Exception{
        /** Store current positions of stage before acquisition. */
        long tic_ = tic();
        try {
            startingXPositionUm = core_.getXPosition(XYStage);
            startingYPositionUm = core_.getYPosition(XYStage);
            if (!ZStage.equals("")) startingZpositionUm = core_.getPosition(ZStage);
            startingMirrorPositionUm = core_.getPosition(mirrorStage);  // um
            runnableLogger.info(String.format(
                    "STAGE START | x=%.3f um | y=%.3f um | z=%.3f um | mirror=%.3f um",
                    startingXPositionUm, startingYPositionUm,
                    ZStage.equals("") ? Double.NaN : startingZpositionUm,
                    startingMirrorPositionUm));
            logTiming("storeStageStartingPositions", tic_);
        } catch (Exception e){
            runnableLogger.severe(String.format(
                    "Failed to get starting stage positions with %s",
                    e.getMessage()));
            throw e;
        }
    }
    
    protected void switchOffLasers(){
        runnableLogger.info("lasers -> off");
        try {
            // core_.setProperty(DAQDOPort, "State", 0);
			core_.setProperty(DAQDOPort, "Blank on", "Low");
            core_.setProperty(DAQDOPort, "Blanking", "On");
        } catch (Exception e){
            String err = "Failed to switch off lasers and blanking via DAQ: " 
                    + e.toString();
            runnableLogger.severe(err);
            logErrorWithWindow(err);
        }
    }
    
    protected void cleanupAcq(){
        long start = System.currentTimeMillis();
        try {
            // Keep outer cleanup lightweight. Individual scan runnables already own
            // their per-scan sequence stop and datastore finalization.
            if (core_.isSequenceRunning()) {
                core_.stopSequenceAcquisition();
            }
			switchOffLasers();
        } catch (Exception e){
            logErrorWithWindow("Issue in stopping sequence " + e.toString());
        }
        runnableLogger.info(String.format("cleanup took %d ms",
                System.currentTimeMillis()-start));
    }
    
    /** Reset stage positions and change them to the travel speed (fast)
     * */
    protected void setStagePositionsToStart(){
        long start = System.currentTimeMillis();
        try {
            long xySpeedTic = tic();
            TangoXYStage.setTangoAxisSpeed(
                    XYStage, deviceSettings.getXyStageTravelSpeed());
            logTiming("setStagePositionsToStart", "setTangoAxisSpeed", xySpeedTic);

            long mirrorVelTic = tic();
            core_.setProperty(mirrorStage, "Velocity", 100);
            logTiming("setStagePositionsToStart", "setMirrorVelocity", mirrorVelTic);

            long xyResetTic = tic();
            // Restore XY explicitly so scan centres do not drift/cumulate between colours.
            TangoXYStage.setXyPosition(XYStage, startingXPositionUm, startingYPositionUm);
            logTiming("setStagePositionsToStart", "resetXY", xyResetTic);

            if (!ZStage.equals("")) {
                long zResetTic = tic();
                core_.setPosition(ZStage, startingZpositionUm);
                logTiming("setStagePositionsToStart", "resetZ", zResetTic);
            }

            long mirrorResetTic = tic();
            core_.setPosition(mirrorStage, startingMirrorPositionUm);
            logTiming("setStagePositionsToStart", "resetMirror", mirrorResetTic);
        } catch (Exception e){
            logErrorWithWindow("Failed to reset stage positions: " + e.getMessage());
        }
        runnableLogger.info(String.format(
                "Time taken moving stages to start: %d ms",
                (System.currentTimeMillis() - start)));
    }
    
    /**
     * Configure the Hamamatsu for one of the three supported acquisition modes.
     *
     *   Mode 0: External global reset
     *   Mode 1: External synchronous readout
     *   Mode 2: Start trigger + internal master clock burst
     *       - Tango still uses an ordinary trigger range that begins at the
     *         scan start, but with an intentionally huge trigger distance so
     *         only the first trigger at scan onset ever occurs
     */
    protected void setupCameraTriggering() throws Exception{
        long start = System.currentTimeMillis();

        core_.setProperty(camName, "ScanMode", "3");
        core_.setProperty(camName, "TriggerPolarity", "POSITIVE");
        core_.setProperty(camName, "OUTPUT TRIGGER POLARITY[1]", "POSITIVE");
        core_.setProperty(camName, "OUTPUT TRIGGER SOURCE[1]", "TRIGGER");

        switch (deviceSettings.getTriggerMode()) {
            case DeviceSettingsManager.TRIGGER_EXTERNAL_GLOBAL:
                runnableLogger.info("Configuring camera for external global-reset triggering");
                core_.setProperty(camName, "MASTER PULSE MODE", "CONTINUOUS");
                core_.setProperty(camName, "MASTER PULSE TRIGGER SOURCE", "EXTERNAL");
                core_.setProperty(camName, "TRIGGER SOURCE", "EXTERNAL");
                core_.setProperty(camName, "TRIGGER ACTIVE", "EDGE");
                core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "GLOBAL RESET");
                core_.setProperty(camName, "OUTPUT TRIGGER KIND[1]", "EXPOSURE");
                break;

            case DeviceSettingsManager.TRIGGER_EXTERNAL_SYNCREADOUT:
                runnableLogger.info("Configuring camera for external synchronous-readout triggering");
                core_.setProperty(camName, "MASTER PULSE MODE", "CONTINUOUS");
                core_.setProperty(camName, "MASTER PULSE TRIGGER SOURCE", "EXTERNAL");
                core_.setProperty(camName, "TRIGGER SOURCE", "EXTERNAL");
                core_.setProperty(camName, "TRIGGER ACTIVE", "SYNCREADOUT");
                core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "DELAYED");
                // Between scans leave the output LOW; the runnable raises HIGH only
                // for the actual scan window.
                core_.setProperty(camName, "OUTPUT TRIGGER KIND[1]", "LOW");
                break;

            case DeviceSettingsManager.TRIGGER_START_INTERNAL_CLOCK:
                runnableLogger.info("Configuring camera for start-triggered internal-clock burst acquisition (single start pulse from ordinary trigger range)");
                core_.setProperty(camName, "TRIGGER SOURCE", "MASTER PULSE");
                core_.setProperty(camName, "TRIGGER ACTIVE", "EDGE");
                core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "DELAYED");
                core_.setProperty(camName, "MASTER PULSE MODE", "START");
                core_.setProperty(camName, "MASTER PULSE TRIGGER SOURCE", "EXTERNAL");
                core_.setProperty(camName, "OUTPUT TRIGGER KIND[1]", "EXPOSURE");
                break;

            default:
                throw new Exception("Unknown trigger mode: " + deviceSettings.getTriggerMode());
        }

        runnableLogger.info(String.format(
                "Time taken setting camera trigger settings: %d ms",
                (System.currentTimeMillis() - start)));
    }

    protected void logCameraTriggerConfig(String label) {
        try {
            runnableLogger.info(String.format(
                    "CAMERA CONFIG | %s | mode=%s | ScanMode=%s | TriggerSource=%s | TriggerActive=%s | TriggerGlobalExposure=%s | MasterPulseMode=%s | MasterPulseTriggerSource=%s | OutputKind=%s",
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
            runnableLogger.warning("Could not log camera trigger config: " + e.getMessage());
        }
    }


    /** Always return the camera to a safe live-imaging state at the end of an acquisition. */
    protected void stopCameraTriggering() throws Exception{
        core_.setProperty(camName, "TRIGGER SOURCE", "INTERNAL");
        core_.setProperty(camName, "TRIGGER ACTIVE", "EDGE");
        core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "DELAYED");
        core_.setProperty(camName, "MASTER PULSE MODE", "CONTINUOUS");
        // core_.setProperty(camName, "OUTPUT TRIGGER KIND[1]", "EXPOSURE"); // this with blanking unecessary light dose
		core_.setProperty(camName, "OUTPUT TRIGGER KIND[1]", "LOW");
    }
    
    protected Datastore createDatastore(PropertyMap customPropertyMap) 
            throws IOException, Exception {
        return createDatastore(mm_.data().summaryMetadataBuilder().build(), 
                customPropertyMap);
    }
    
    /**
     * Empty property map, but supply summary metadata *
     * @param metadata summary metadata to put in datastore
     * @return empty datastore with summary metadata
     * @throws IOException
     * @throws Exception 
     */
    protected Datastore createDatastore(SummaryMetadata metadata) 
            throws IOException, Exception {
        return createDatastore(metadata, PropertyMaps.builder().build());
    }
    
    /** Create datastore for acquisition using the supplied data save path,
     * filename will be MMStack.
     */
    protected Datastore createDatastore(SummaryMetadata metadata, 
            PropertyMap customPropertyMap) throws IOException, Exception{
        double storeStartTime = System.currentTimeMillis();
        Datastore store;
        String stackDirName;
        boolean separateMetadata = true;
        
        if (currentAcq!=null){
            stackDirName = String.format("dOPM_t%04d_p%04d_z%04d_c%04d_view%d", 
                    currentAcq.getCurrentAcqTimeIdx(),
                    currentAcq.getCurrentAcqPositionIdx(),
                    currentAcq.getCurrentAcqZIdx(),
                    currentAcq.getCurrentAcqChannelIdx(),
                    currentAcq.getCurrentView()
                );    
        } else{
            int i=0;
            while(new File(dataOutDir, String.format("MMStack_n%04d", i)).exists()){ i++; }
            stackDirName = (String.format("MMStack_n%04d", i));
        }
        
        String dataSavePath = (new File(dataOutDir, stackDirName)).getAbsolutePath();
        
        try {
            runnableLogger.info("creating datastore in " + dataSavePath);
            // EXCLUSIVELY NDTIFF (last parameter true) for background saving
            store = FileMM.createDatastore(camName, dataSavePath, true, separateMetadata, true);
        } catch (Exception e){
            throw new Exception("Failed to create datastore with " + e.getMessage());
        }
        
        PropertyMap myPropertyMap; 
        try {
            runnableLogger.info("Getting more metadata");
            runnableLogger.info("angle " + currentViewAngle);
            runnableLogger.info("filter " + deviceSettings.getCurrentFilter());
            runnableLogger.info("laser " + deviceSettings.getCurrentLaser());
            runnableLogger.info("power " + deviceSettings.getCurrentLaserPower());
            runnableLogger.info("exposureMs " + core_.getExposure()); 
            runnableLogger.info("positionLabel " + currentAcq.getCurrentAcqPositionLabel());
            runnableLogger.info("positionIdx " + currentAcq.getCurrentAcqPositionIdx());
            runnableLogger.info("channelGroup " + currentAcq.getCurrentAcqChannel().channelGroup());
            runnableLogger.info("channelIdx " + currentAcq.getCurrentAcqChannelIdx());
            runnableLogger.info("zSlice " + currentAcq.getCurrentAcqZ());
            runnableLogger.info("zSliceIdx " + currentAcq.getCurrentAcqZIdx());
            runnableLogger.info("time ms " + currentAcq.getCurrentAcqTime());
            runnableLogger.info("time mins " + currentAcq.getCurrentAcqTime()/60000);
            runnableLogger.info("timeIdx" + currentAcq.getCurrentAcqTimeIdx());

            myPropertyMap = PropertyMaps.builder().
                putDouble("angle", currentViewAngle).
                putString("filter", deviceSettings.getCurrentFilter()).
                putString("laser", deviceSettings.getCurrentLaser()).
                putDouble("power", deviceSettings.getCurrentLaserPower()).
                putDouble("exposureMs", core_.getExposure()).
                putDouble("x", startingXPositionUm).
                putDouble("y", startingYPositionUm).
                putDouble("z", startingZpositionUm).
                putString("positionLabel", currentAcq.getCurrentAcqPositionLabel()).
                putInteger("positionIdx", currentAcq.getCurrentAcqPositionIdx()).
                putString("channelGroup", currentAcq.getCurrentAcqChannel().channelGroup()).
                putInteger("channelIdx", currentAcq.getCurrentAcqChannelIdx()).
                putDouble("zSlice", currentAcq.getCurrentAcqZ()).
                putInteger("zSliceIdx", currentAcq.getCurrentAcqZIdx()).
                putDouble("time (ms)", currentAcq.getCurrentAcqTime()).
                putDouble("time (mins)", currentAcq.getCurrentAcqTime()/60000).
                putInteger("timeIdx", currentAcq.getCurrentAcqTimeIdx()).
                putAll(customPropertyMap).
                    build();
        } catch (Exception e){
            myPropertyMap = PropertyMaps.builder().build();
        } 

        metadata = metadata.copyBuilder().userData(myPropertyMap).build();
        store.setSummaryMetadata(metadata);
        
        runnableLogger.info(String.format("Datastore creation time: %.2f ms", 
                (double)(System.currentTimeMillis() - storeStartTime)));
        return store;
    }
    
    protected Datastore acquireTriggeredDataset(
            Datastore store, int nFramesTotal) 
            throws Exception {
        return acquireTriggeredDataset(store, nFramesTotal, 10000);
    }
    
    protected Datastore acquireTriggeredDataset(
            Datastore store, int nFramesTotal, int timeOutMs)
            throws Exception {
        boolean timeout = false;
        double acqTimeStart = System.currentTimeMillis();
        Coords.Builder cb = mm_.data().coordsBuilder().p(0);

        boolean grabbed = false;
        int nFrames = 0;
        double frameTimeTotal = 0;
        double firstFrameLatencyMs = -1;
        int nextProgressFrame = Math.max(1, nFramesTotal / 4);
        
        double magnification =  6.5 / (1.333 * (50 / (180 / 50)));
        double pxSizeUm = 6.5/magnification;
        runnableLogger.info("Pixel size (um) is " + pxSizeUm);
        Metadata.Builder md = mm_.data().metadataBuilder().pixelSizeUm(pxSizeUm);

        while (nFrames < nFramesTotal && !timeout){
                double tic=System.currentTimeMillis();
                double toc=tic;

                grabbed = false;
                while(toc-tic < timeOutMs && !grabbed){
                    if (core_.getRemainingImageCount() > 0){
                        // LITERAL REPO TaggedImage LOOP RESTORED
                        TaggedImage img = core_.popNextTaggedImage();	
                        Image tmp = mm_.data().convertTaggedImage(img);  
                        Image cbImg = tmp.copyWith(cb.p(nFrames).build(), md.build());
                        store.putImage(cbImg);
                        grabbed = true;
                        nFrames++;
                        if (firstFrameLatencyMs < 0) {
                            firstFrameLatencyMs = System.currentTimeMillis() - acqTimeStart;
                            runnableLogger.info(String.format(
                                    "TIMING | acquireTriggeredDataset | firstFrameLatency | %.1f ms",
                                    firstFrameLatencyMs));
                        }
                        if (nFrames >= nextProgressFrame && nFrames < nFramesTotal) {
                            runnableLogger.info(String.format(
                                    "ACQ PROGRESS | %d / %d frames | elapsed %.1f ms",
                                    nFrames, nFramesTotal,
                                    (double)(System.currentTimeMillis() - acqTimeStart)));
                            nextProgressFrame += Math.max(1, nFramesTotal / 4);
                        }
                    }
                    toc = System.currentTimeMillis(); 
                }
                if (toc-tic >= timeOutMs){
                    int dropped = (nFramesTotal-nFrames);
                    runnableLogger.severe(String.format("%d FRAMES DROPPED", dropped));
                    timeout = true;  
                    if (nFrames==0){
                        throw new TimeoutException("No frames acquired in triggered acquisition.");
                    } else if (dropped > maxDroppedFrames) {
                        throw new TimeoutException(String.format("%d frames dropped", dropped));
                    }
                }
                frameTimeTotal += (toc-tic);
        }
        
        runnableLogger.info(String.format("Frames acquired: %s (%d dropped)", 
                nFrames, (nFramesTotal-nFrames)));
        runnableLogger.info(String.format("Actual effective FPS: %.2f", 
                1e3*nFrames/frameTimeTotal));
        runnableLogger.info(String.format("Total time in acquireTriggeredDataset %.1f ms", (double)(System.currentTimeMillis() - acqTimeStart)));

        return store;
    }
}