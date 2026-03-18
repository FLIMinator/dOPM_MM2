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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
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
import loci.formats.meta.BaseMetadata;

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
        try {
            // wait until stage has reached position in position list
            core_.waitForSystem();
        } catch (Exception e){
            String msg = "Failed to wait for devices before "
                    + "acquisition with " + e.toString();
            runnableLogger.severe(msg);
            logErrorWithWindow(e);
            // Thread.sleep(10000);
        }
        long start = System.currentTimeMillis();           
        
        // Set scan speed variables accordingly for mirror and xystage
        deviceSettings.updateCurrentScanSpeedsDuringAcq();
                
        try {
            // Setup Camera Triggering using provided logic
            setupCameraTriggering();
            
            PIStage.setPITriggerLow(mirrorStagePort);
            TangoXYStage.setTangoTriggerEnable(XYStagePort, 0);

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
                try {
                    storeStageStartingPositions();
                    runSingleView(currentViewAngle);
                } catch (Exception e){
                    logErrorWithWindow(e);
                } finally {
                    cleanupAcq();
                    setStagePositionsToStart();
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
                try {
                    storeStageStartingPositions();
                    runSingleView(currentViewAngle);
                } catch (Exception e){
                    logErrorWithWindow(e);
                } finally {
                    cleanupAcq();
                    setStagePositionsToStart();
                }
            }
        } catch (Exception hardwareEx) {
            logErrorWithWindow("Hardware setup logic fail: " + hardwareEx.getMessage());
        } finally {
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
        try {
            startingXPositionUm = core_.getXPosition(XYStage);
            startingYPositionUm = core_.getYPosition(XYStage);
            if (!ZStage.equals("")) startingZpositionUm = core_.getPosition(ZStage);
            startingMirrorPositionUm = core_.getPosition(mirrorStage);  // um
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
            core_.setProperty(DAQDOPort, "State", 0);
            core_.setProperty(DAQDOPort, "Blanking", "Off");
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
            double stopAcqStart = System.currentTimeMillis();
            if (core_.isSequenceRunning()){
                core_.stopSequenceAcquisition();
                // RECENT IMPROVEMENT: Drain Buffer to prevent stall
                int timeout = 0;
                while (core_.getRemainingImageCount() > 0 && timeout < 200) {
                    Thread.sleep(10); timeout++;
                }
            }
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
            TangoXYStage.setTangoAxisSpeed(
                    XYStage, deviceSettings.getXyStageTravelSpeed());
            core_.setProperty(mirrorStage, "Velocity", 100);
            
            // THE DRIFT FIX: Restore the move back to the original Center coordinates
            TangoXYStage.setXyPosition(XYStage, startingXPositionUm, startingYPositionUm);
            
            if (!ZStage.equals("")) core_.setPosition(ZStage, startingZpositionUm);
            core_.setPosition(mirrorStage, startingMirrorPositionUm);
        } catch (Exception e){
            logErrorWithWindow("Failed to reset stage positions: " + e.getMessage());
        }
        runnableLogger.info(String.format(
                "Time taken moving stages to start: %d ms",
                (System.currentTimeMillis() - start)));
    }
    
    protected void setupCameraTriggering() throws Exception{
        long start = System.currentTimeMillis();
        core_.setProperty(camName, "ScanMode", "3");
        
        // YOUR VERIFIED TRIGGER SETTINGS
        if (deviceSettings.getTriggerMode() == 2) {
            runnableLogger.info("Configuring Camera for MASTER PULSE START (Starter Pistol)");
            core_.setProperty(camName, "TRIGGER SOURCE", "MASTER PULSE");
            core_.setProperty(camName, "TRIGGER ACTIVE",  "EDGE");
            core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "DELAYED"); 
            core_.setProperty(camName, "OUTPUT TRIGGER KIND[0]", "EXPOSURE");
            core_.setProperty(camName, "MASTER PULSE MODE", "START");
            core_.setProperty(camName, "MASTER PULSE TRIGGER SOURCE", "EXTERNAL");
        } else {
            // TIDY DEFAULT: Master Pulse Continuous for stable sync
            core_.setProperty(camName, "MASTER PULSE MODE", "CONTINUOUS");
            core_.setProperty(camName, "TRIGGER SOURCE", "EXTERNAL");
            core_.setProperty(camName, "TRIGGER ACTIVE", "EDGE");
            core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "GLOBAL RESET");
            core_.setProperty(camName, "OUTPUT TRIGGER KIND[0]", "EXPOSURE");
        }

        core_.setProperty(camName, "TriggerPolarity","POSITIVE");
        core_.setProperty(camName, "OUTPUT TRIGGER POLARITY[0]","POSITIVE");
        core_.setProperty(camName, "OUTPUT TRIGGER SOURCE[0]","TRIGGER");
        runnableLogger.info(String.format(
                "Time taken setting camera trigger settings: %d ms",
                (System.currentTimeMillis() - start)));
    }
    
    /** Reset camera state to prevent mode stickiness on Return to Live */
    protected void stopCameraTriggering() throws Exception{
        core_.setProperty(camName, "TRIGGER SOURCE","INTERNAL");
        core_.setProperty(camName, "TRIGGER ACTIVE", "EDGE");
        core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE", "GLOBAL RESET");
        core_.setProperty(camName, "MASTER PULSE MODE", "CONTINUOUS");
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
            // Get my MDAProgressManager metadata
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
            runnableLogger.severe("Failed to create datastore metadata, falling"
                                    + " back to default summary metadata" + e.getMessage());
            myPropertyMap = PropertyMaps.builder().build();
        } 

        // copy existing metadata (might well be empty)
        metadata = metadata.copyBuilder().
                userData(myPropertyMap).build();
        store.setSummaryMetadata(metadata);
        
        double storeCreationTime = System.currentTimeMillis() - storeStartTime;
        runnableLogger.info(String.format("Datastore creation time: %.2f ms", 
                storeCreationTime));
        return store;
    }
    
    /** Loop to grab frames from a camera that is being hardware triggered */
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
        int frameTimeout = timeOutMs; 
        
        double magnification = 20.0*1.406*(200.0/180.0);
        double pxSizeUm = 6.5/magnification;
        runnableLogger.info("Pixel size (um) is " + pxSizeUm);
        Metadata.Builder md = mm_.data().metadataBuilder().pixelSizeUm(pxSizeUm);

        while (nFrames < nFramesTotal && !timeout){
                double tic=System.currentTimeMillis();
                double toc=tic;

                grabbed = false;
                while(toc-tic < timeOutMs && !grabbed){
                    if (core_.getRemainingImageCount() > 0){
                        // TaggedImage LOOP RESTORED
                        TaggedImage img = core_.popNextTaggedImage();	
                        Image tmp = mm_.data().convertTaggedImage(img);  
                        Image cbImg = tmp.copyWith(cb.p(nFrames).build(), md.build());
                        store.putImage(cbImg);
                        grabbed = true;
                        nFrames++;
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