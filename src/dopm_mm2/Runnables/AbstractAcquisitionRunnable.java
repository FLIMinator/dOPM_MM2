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
        
        runnableLogger.info(String.format("Variables: "
                + "camera: %s, "
                + "mirror stage: %s, "
                + "xy stage: %s, "
                + "z stage: %s, "
                + "filter wheel: %s, "
                + "DAQ DO port: %s",
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
            // REVISION: Added responsiveness to the GUI stop button
            checkInterrupt();
            // wait until stage has reached position in position list
            core_.waitForSystem();
        } catch (InterruptedException ie) {
            handleInterrupt(ie);
            return;
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
        // REVISION: Snapshot system state for the log
        deviceSettings.logSystemState();
                
        // Enable external triggering if applicable, 
        // maybe move this for readability?
        if (deviceSettings.getTriggerMode() != 2){
            try {
                setupCameraTriggering();
            } catch (Exception e){
                logErrorWithWindow(String.format(
                    "Failed to set camera trigger settings and enable external"
                    + "triggering with error %s",
                    e.getMessage()));
            }
        }
        try {
            PIStage.setPITriggerLow(mirrorStagePort);
            TangoXYStage.setTangoTriggerEnable(XYStagePort, 0);
        } catch (Exception e){
            logErrorWithWindow(String.format(
                    "Failed to set PI trigger to low with error %s",
                    e.getMessage()));
        }
        
        runnableLogger.info(String.format("Runnable setup inside run took %d ms", 
                System.currentTimeMillis()-start));
        
        // // // // // // // // // // // // // // // // // // // // // // // // 
        // ACQUISITION SECTION --------------------------------------------- //
        // ------- Do view 1 or view 2 (or both), calls runSingleView ------ //
        // // // // // // // // // // // // // // // // // // // // // // // // 
        
        boolean acqSuccess = false;  // use for retries, for future use TODO

        // View 1
        long view1start = System.currentTimeMillis();
        if (deviceSettings.isView1Imaged()){
            runnableLogger.info("Acquiring view 1");
            // this config should be set automatically if it doesnt exist from 
            // PIViewPositions.txt (TODO)
            try { 
                checkInterrupt();
                currentAcq.setCurrentView(1);
            } catch (InterruptedException ie) {
                handleInterrupt(ie);
                return;
            } catch (Exception e){
                runnableLogger.severe("Failed to change to view 1 with " + e.toString()
                        + " check config dOPM View exists with preset View 1");
                return;
            }
            // I think hugh would do 0 and 90 instead of -45 and 45, check
            //runOneView(-deviceSettings.getOpmAngle());  
            currentViewAngle = -deviceSettings.getOpmAngle();
            try {
                storeStageStartingPositions();
                runSingleView(currentViewAngle);
            } catch (InterruptedException ie) {
                handleInterrupt(ie);
                return;
            } catch (Exception e){
                logErrorWithWindow(e);
                
            } finally {
                cleanupAcq();
                setStagePositionsToStart();
            }

        }
        runnableLogger.info(String.format(
                "View 1 acquisition (%s) run took %d ms", 
                this.getClass().getName(),
                System.currentTimeMillis()-view1start));
        
        // View 2
        long view2start = System.currentTimeMillis();
        if (deviceSettings.isView2Imaged()){
            runnableLogger.info("Acquiring view 2");
            try { 
                checkInterrupt();
                currentAcq.setCurrentView(2);
            } catch (InterruptedException ie) {
                handleInterrupt(ie);
                return;
            } catch (Exception e){
                runnableLogger.severe("Failed to change to view 2 with " + e.toString()
                        + "check config dOPM View exists with preset View 2");
            }
            currentViewAngle = deviceSettings.getOpmAngle();
            try {
                storeStageStartingPositions();
                runSingleView(currentViewAngle);
            } catch (InterruptedException ie) {
                handleInterrupt(ie);
                return;
            } catch (Exception e){
                logErrorWithWindow(e);
            } finally {
                cleanupAcq();
                setStagePositionsToStart();
            }
        }
        runnableLogger.info(String.format(
                "View 2 acquisition (%s) run took %d ms", 
                this.getClass().getName(),
                System.currentTimeMillis()-view2start));
        
        // // // // // // // // // // // // // // // // // // // // // // // // 
        
        try {
            stopCameraTriggering();
        } catch (Exception e){
            logErrorWithWindow("Failed to switch camera to internal triggering "
                    + "with " + e.getMessage());
        }
        
        // Update the MDA indices, IMPORTANT FOR FILE SAVING/METADATA!
        if (currentAcq!=null){  // should never be null, i removed the ability for that
            currentAcq.nextAcqPoint();
        }
        
        runnableLogger.info(String.format(
                "Full acquisition in runnable %s took %d ms", 
                this.getClass().getName(),
                System.currentTimeMillis()-start));
        endClockTimeMs = System.currentTimeMillis();
    }
    
    public void runSingleView(double currentViewAngle) throws Exception{
        // MAIN BODY OF CODE GOES HERE, use @Override
    }

    /** REVISION: checks internal flag and MDA status for responsiveness to "Stop" button. */
    protected void checkInterrupt() throws InterruptedException {
        boolean mdaIsStopped = !mm_.acquisitions().isAcquisitionRunning();
        if (Thread.interrupted() || frame_.getInterruptFlag() || mdaIsStopped) {
            throw new InterruptedException("Acquisition aborted by user.");
        }
    }

    /** REVISION: Safely resets hardware after an interrupt. */
    protected void handleInterrupt(InterruptedException ie) {
        runnableLogger.warning("Interrupt detected: Resetting hardware to safe state...");
        frame_.setInterruptFlag(false);
        cleanupAcq();
        setStagePositionsToStart();
        switchOffLasers();
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
        
        // cleanupAcq();
        // setStagePositionsToStart();
        
        acquisitionFailed = true;
        if (errorWindowsDuringAcq) dialogBoxes.acquisitionErrorWindow(msg);
        
    }
    
    protected void storeStageStartingPositions() throws Exception{
        /** Store current positions of stage before acquisition.
         * Important to make sure it's correct, this stores x,y positions that
         * are used in the runSingleView methods which do the imaging routines
         * if it's wrong e.g., we don't wait until stage is stopped in the 
         * correct position list position, it will be wrong.
         */
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
    
    // perhaps a little redundant atm
    protected void cleanupAcq(){
        long start = System.currentTimeMillis();
        try {
            double stopAcqStart = System.currentTimeMillis();
            if (core_.isSequenceRunning()){
                core_.stopSequenceAcquisition();
                String err = String.format(
                        "Previous sequence wasn't ended, stopped in %.1f ms",
                        (System.currentTimeMillis() - stopAcqStart) );
                runnableLogger.warning(err);
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
            // had previous issue of tango stage not being ready to move, fixed
            TangoXYStage.setXyPosition(XYStage, startingXPositionUm, startingYPositionUm);
            // core_.setXYPosition(XYStage, startingXPositionUm, startingYPositionUm);
            if (!ZStage.equals("")) core_.setPosition(ZStage, startingZpositionUm);
            core_.setPosition(mirrorStage, startingMirrorPositionUm);
        } catch (Exception e){
            logErrorWithWindow("Failed to reset stage "
                    + "positions after acquisition with " + 
                    e.getMessage());
        }
        runnableLogger.info(String.format(
                "Time taken moving stages to start: %d ms",
                (System.currentTimeMillis() - start)));
    }
    
    protected void setupCameraTriggering() throws Exception{
        long start = System.currentTimeMillis();
        // REVISION: Mandatory enforcement of ScanMode 3 for readout timing accuracy
        core_.setProperty(camName, "ScanMode", "3");
        core_.setProperty(camName, "TriggerPolarity","POSITIVE");
        core_.setProperty(camName, "TRIGGER SOURCE","EXTERNAL");
        core_.setProperty(camName, "OUTPUT TRIGGER KIND[0]","EXPOSURE");
        core_.setProperty(camName, "OUTPUT TRIGGER POLARITY[0]","POSITIVE");
        core_.setProperty(camName, "OUTPUT TRIGGER SOURCE[0]","TRIGGER");
        core_.setProperty(camName, "TRIGGER GLOBAL EXPOSURE","GLOBAL RESET");
        runnableLogger.info(String.format(
                "Time taken setting camera trigger settings: %d ms",
                (System.currentTimeMillis() - start)));
    }
    
    /** Switches back to internal triggering: A bit pointless but just to keep 
     * everything consistent */
    protected void stopCameraTriggering() throws Exception{
        core_.setProperty(camName, "TRIGGER SOURCE","INTERNAL");
    }
    
    
    // TODO overall the createDatastores to only receive propertyMap and just
    // parse the fields of that. e.g. propertyMap contains zStepUm and gets
    // read into the summaryMetadata
    /**
     * Empty summaryMetadata, but supply custom PropertyMap
     * @param customPropertyMap propertyMap that gets built into summary metadata
     * @return empty datastore
     * @throws IOException
     * @throws Exception
     */
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
     * @param customPropertyMap property map to be created in runSingleView,
     * use PropertyMaps.builder to build the property map that has e.g. 
     * scan length, scan type, trigger distance, optional
     * @param metadata summary metadata supplied so that e.g. z spacing is 
     * saved, optional
     * @return the empty datastore with metadata
     * @throws IOException if datastore creation fails (in FileMM)
     */
    protected Datastore createDatastore(SummaryMetadata metadata, 
            PropertyMap customPropertyMap) throws IOException, Exception{
        double storeStartTime = System.currentTimeMillis();
        Datastore store;
        String stackDirName;
        boolean useNDTiff = false; // TODO: add ability to change in gui
        boolean separateMetadata = true;
        
        // get file name based on position in MDA. consider using device 
        // settings to save into filename laser, power, exposure, filter and 
        // then the currentAcq just for time, position, z scan plane (if used)
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
            while(new File(dataOutDir, 
                    String.format("MMStack_n%04d", i)).exists()){
                i++;
            }
            stackDirName = (String.format("MMStack_n%04d", i));
        }
        
        String dataSavePath = (new File(dataOutDir, stackDirName)).getAbsolutePath();
        
        try {
            runnableLogger.info("creating datastore in " + dataSavePath);
            // false -- normal ome tiff, true -- new ndtiff
            store = FileMM.createDatastore(camName, dataSavePath, 
                    true, separateMetadata, useNDTiff);
        } catch (IOException ie){
            throw new IOException("Failed to create datastore with "
                    + ie.getMessage());
        } catch (Exception e){
            throw new Exception("Uknown error when creating datastore with "
                    + e.getMessage());
        }
        
        PropertyMap myPropertyMap; 
        try {
            // Get my MDAProgressManager metadata
            // possibly redudant, this was just used to save file
            
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
            runnableLogger.info("time mins " + currentAcq.getCurrentAcqTime());
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
    
    /**
     * Loop to grab frames from a camera that is being hardware triggered
     * @param store
     * @param nFramesTotal
     * @return
     * @throws Exception
     */
    protected Datastore acquireTriggeredDataset(
            Datastore store, int nFramesTotal) 
            throws Exception {
        return acquireTriggeredDataset(store, nFramesTotal, 10000);
    }
    
    protected Datastore acquireTriggeredDataset(
            Datastore store, int nFramesTotal, int timeOutMs)
            throws Exception {
        // Coords.Builder cb = Coordinates.builder();
        boolean timeout = false;
        double acqTimeStart = System.currentTimeMillis();
    
        // Coords.Builder cb = mm_.data().coordsBuilder().z(0).channel(0).stagePosition(0);
        Coords.Builder cb = mm_.data().coordsBuilder().p(0);

        boolean grabbed = false;
        int nFrames = 0;
        double frameTimeTotal = 0;
        int frameTimeout = timeOutMs; // if no frame received for 10s, time out
        
        // REVISION Layer 9: Link math directly to DeviceSettingsManager
        double magnification = deviceSettings.getMagnification(); // Uses dynamic GUI value
        double pxSizeUm = 6.5 / magnification; // Calculated in sample plane
        
        runnableLogger.info("Pixel size (um) is " + pxSizeUm + " (System Magnification: " + magnification + ")");
        
        Metadata.Builder md = 
                mm_.data().metadataBuilder().pixelSizeUm(pxSizeUm);

        while (nFrames < nFramesTotal && !timeout){
                
                // REVISION: Poll Stop Button status inside loop
                checkInterrupt();

                double tic=System.currentTimeMillis();
                double toc=tic;

                grabbed = false;
                while(toc-tic < frameTimeout && !grabbed){
                    // wait for an image in the circular buffer
                    if (core_.getRemainingImageCount() > 0){
                        TaggedImage img = core_.popNextTaggedImage();	// TaggedImage
                        // System.out.println("TAGS: " + img.tags.toString());
                        
                        // runnableLogger.info("Got tagged image:" + nFrames);
                        Image tmp = mm_.data().convertTaggedImage(img);  // Image

                        // does this copy in memory? inefficient?
                        Image cbImg = tmp.copyWith(cb.p(nFrames).build(), 
                                md.build());
                        store.putImage(cbImg);
                        grabbed = true;
                        nFrames++;
                    }
                    toc = System.currentTimeMillis(); 
                    // REVISION: responsive during wait states
                    if (toc - tic > 500) checkInterrupt();
                }
                if (toc-tic >= frameTimeout){
                    int dropped = (nFramesTotal-nFrames);
                    runnableLogger.severe(String.format(
                            "%d FRAMES DROPPED", dropped));
                    timeout = true;  // actually redundant
                    if (nFrames==0){
                        runnableLogger.severe("No frames acquired");
                        throw new TimeoutException("No frames acquired in triggered "
                            + "acquisition. Check hardware and wiring");
                    } else if (dropped > maxDroppedFrames) {
                        runnableLogger.severe("Not all frames acquired");
                        String msg = String.format(
                                "%d frames dropped (maximum allowed=%d) in "
                                + "triggered acquisition, "
                                + "check camera speed settings, trigger "
                                + "distance, exposure, scan speed. If using "
                                + "max scan speed, consider reducing the '"
                                + "fraction of max factor, especially for "
                                + "higher exposure times", 
                                dropped, maxDroppedFrames);
                        
                        throw new TimeoutException(msg);
                    }
                }
                frameTimeTotal += (toc-tic);
        }
        
        double acqTimeStop = System.currentTimeMillis() - acqTimeStart; 
        
        runnableLogger.info(String.format("Frames acquired: %s (%d dropped)", 
                nFrames, (nFramesTotal-nFrames)));
        runnableLogger.info(String.format("Actual effective FPS: %.2f", 
                1e3*nFrames/frameTimeTotal));

        runnableLogger.info(String.format("Time in frame grabbing loop %.1f ms", 
                frameTimeTotal));
        runnableLogger.info(String.format("Total time in "
                + "acquireTriggeredDataset %.1f ms", acqTimeStop));

        return store;
    }
}