/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Devices;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.File;
import java.io.IOException;
import java.beans.XMLEncoder;
import java.util.*;
import java.util.logging.Logger;
import java.awt.Rectangle;
import javax.swing.JOptionPane;

import mmcorej.CMMCore;
import mmcorej.StrVector;
import mmcorej.Configuration;
import mmcorej.PropertySetting;

import dopm_mm2.util.MMStudioInstance;
import dopm_mm2.util.ConfigParser;

import org.micromanager.Studio;
import org.micromanager.acquisition.AcquisitionManager;
import org.micromanager.acquisition.ChannelSpec;
import org.micromanager.acquisition.SequenceSettings;

/**
 * DeviceSettingsManager: class for handling device names in Micro-Manager configuration.
 *
 * This class combines:
 * - stored device/settings state
 * - validation against the loaded MM device list
 * - lightweight MM Core wrappers
 * - scan/exposure helper calculations
 *
 * Author: lnr19
 *
 * REVISION NOTES (Layer 1.5):
 * - MDA-Aware logic: prioritizes Acquisition Settings exposure over Live Core exposure.
 * - Added logSystemState() for hardware traceability.
 */
public class DeviceSettingsManager {

    // ---------------------------------------------------------------------
    // Logger
    // ---------------------------------------------------------------------

    private static final Logger deviceManagerLogger =
            Logger.getLogger(DeviceSettingsManager.class.getName());

    // ---------------------------------------------------------------------
    // Public constants
    // ---------------------------------------------------------------------

    public final static int MIRROR_SCAN = 0;
    public final static int YSTAGE_SCAN = 1;
    public final static int XSTAGE_SCAN = 2;

    /** Camera trigger modes used throughout the plugin. */
    public final static int TRIGGER_EXTERNAL_GLOBAL = 0;
    public final static int TRIGGER_EXTERNAL_SYNCREADOUT = 1;
    public final static int TRIGGER_START_INTERNAL_CLOCK = 2;

    // ---------------------------------------------------------------------
    // Micro-Manager handles
    // ---------------------------------------------------------------------

    public CMMCore core_ = null;
    public Studio mm_ = null;

    // ---------------------------------------------------------------------
    // Device names / ports / loaded device list
    // ---------------------------------------------------------------------

    private List<String> laserDeviceNames;
    private List<String> laserLabels;
    private String laserBlankingDOport;
    private List<String> laserBlankingDOLines;
    private List<String> laserPowerAOports;

    private String filterDeviceName;
    private String dOPMCameraName;
    private StrVector deviceList;

    private String xyStageName;
    private String xyStageComPort;

    private String mirrorStageName;
    private String mirrorStageComPort;

    private String zStageName;
    private String zStageComPort;

    // ---------------------------------------------------------------------
    // Acquisition / channel state
    // ---------------------------------------------------------------------

    private List<String> laserChannelsAcq;
    private List<String> laserPowersAcq;
    private List<String> filtersAcq;
    private List<Object> laserDAQdevices;

    private int currentAcqChannel;

    // ---------------------------------------------------------------------
    // Scan / trigger settings
    // ---------------------------------------------------------------------

    private int scanType = MIRROR_SCAN;
    private int triggerMode;
    private String[] triggerModeStrings;

    private double xyStageScanLength;
    private double xyStageTriggerDistance;
    private String xyStageScanAxis;
    private boolean useMaxScanSpeedForXyStage = false;

    private double mirrorScanLength;
    private double mirrorTriggerDistance;
    private boolean useMaxScanSpeedForMirror = false;

    private double scanSpeedSafetyFactorMirror;
    private double scanSpeedSafetyFactorXy;

    // ---------------------------------------------------------------------
    // Derived scan-speed limits
    // ---------------------------------------------------------------------

    private double maxTriggeredMirrorScanSpeed;
    private double maxTriggeredXyScanSpeed;
    private double maxGlobalTriggeredMirrorScanSpeed;
    private double maxGlobalTriggeredXyScanSpeed;

    // ---------------------------------------------------------------------
    // Stage speeds / limits
    // ---------------------------------------------------------------------

    private int[] z_lim;

    private double xyStageTravelSpeed;
    private double xyStageCurrentScanSpeed;
    private double xyStageGlobalScanSpeed;

    private double mirrorStageTravelSpeed;
    private double mirrorStageCurrentScanSpeed;
    private double mirrorStageGlobalScanSpeed;

    private double zStageTravelSpeed;

    // ---------------------------------------------------------------------
    // Optical / imaging settings
    // ---------------------------------------------------------------------

    private double immersionRI;
    private double opmAngle;
    private double magnification;

    private boolean imageView1;
    private boolean imageView2;
    private boolean saveAcquisitionLogs;

    private double exposureTime;
    private double actualExposureTime;
    private Rectangle frameSize;

    // =========================================================================
    // Construction
    // =========================================================================

    public DeviceSettingsManager() {
        core_ = MMStudioInstance.getCore();
        mm_ = MMStudioInstance.getStudio();
        initVars();
    }

    public DeviceSettingsManager(CMMCore cmmcore) {
        core_ = cmmcore;
        mm_ = MMStudioInstance.getStudio();
        initVars();
        loadAllDeviceNames();
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Initialize all internal settings to defaults.
     */
    private void initVars() {
        triggerModeStrings = new String[] {
            "External trigger (global reset)",
            "External trigger (synchronous readout)",
            "Untriggered (start trigger + internal clock)"
        };

        z_lim = new int[] { -12000000, 1000000 };

        laserDeviceNames = new ArrayList<>();
        laserBlankingDOLines = new ArrayList<>();
        laserPowerAOports = new ArrayList<>();

        filterDeviceName = "";
        dOPMCameraName = "";
        deviceList = null;

        scanType = MIRROR_SCAN;

        mirrorScanLength = 50.0;
        mirrorTriggerDistance = 1.0;
        triggerMode = 0;
        useMaxScanSpeedForMirror = false;

        scanSpeedSafetyFactorMirror = 0.95;
        scanSpeedSafetyFactorXy = 0.95;

        maxTriggeredMirrorScanSpeed = 0.01;
        maxTriggeredXyScanSpeed = 0.01;
        maxGlobalTriggeredMirrorScanSpeed = 0.1;
        maxGlobalTriggeredXyScanSpeed = 0.1;

        xyStageScanLength = 50.0;
        xyStageTriggerDistance = 1.0;
        xyStageScanAxis = "y";
        useMaxScanSpeedForXyStage = false;

        try {
            xyStageName = core_.getXYStageDevice();
        } catch (Exception e) {
            xyStageName = "";
        }

        xyStageTravelSpeed = 10.0;
        xyStageCurrentScanSpeed = 0.01;
        xyStageGlobalScanSpeed = 0.01;
        xyStageComPort = getPortProperty(xyStageName);

        mirrorStageName = "";
        mirrorStageTravelSpeed = 10.0;
        mirrorStageCurrentScanSpeed = 0.01;
        mirrorStageGlobalScanSpeed = 0.01;
        mirrorStageComPort = "COM3";

        try {
            zStageName = core_.getFocusDevice();
        } catch (Exception e) {
            zStageName = "";
        }

        zStageTravelSpeed = 10.0;
        zStageComPort = "";

        immersionRI = 1.33;
        imageView1 = true;
        imageView2 = false;
        opmAngle = 35;
        magnification = 6.5 / (1.333 * (50 / (180 / 50)));
        saveAcquisitionLogs = true;

        exposureTime = 5.0;
        actualExposureTime = 5.0;
        frameSize = new Rectangle(0, 0, 2304, 2304);
    }

    // =========================================================================
    // System loading / serialization
    // =========================================================================

    private void loadAllDeviceNames() {
        setDeviceList(core_.getLoadedDevices());
    }

    public void loadSystemSettings(String configDetailsJson) {
        List<String> expectedKeys = Arrays.asList(new String[] {
            "laser_devices", "laser_labels", "laser_daq_do_port", "laser_daq_blanking_lines",
            "laser_daq_ao_ports", "camera_dopm", "camera_right", "filter", "xy_stage",
            "z_stage", "mirror_stage", "xy_stage_com_port", "z_stage_com_port",
            "mirror_stage_com_port", "refractive_index", "opm_angle", "magnification"
        });

        try {
            ConfigParser configParser = new ConfigParser(configDetailsJson, expectedKeys);
            configParser.parse();
            HashMap<String, List<String>> configMap = configParser.getConfigMap();

            setdOPMCameraName(configMap.get("camera_dopm"));
            setLaserLabels(configMap.get("laser_labels"));
            setLaserBlankingDOport(configMap.get("laser_daq_do_port"));
            setLaserBlankingDOLines(configMap.get("laser_daq_blanking_lines"));
            setLaserPowerAOports(configMap.get("laser_daq_ao_ports"));
            setFilterDeviceName(configMap.get("filter"));
            setXyStageName(configMap.get("xy_stage"));
            setZStageName(configMap.get("z_stage"));
            setMirrorStageName(configMap.get("mirror_stage"));
            setXyStageComPort(configMap.get("xy_stage_com_port"));
            setMirrorStageComPort(configMap.get("mirror_stage_com_port"));
            setZStageComPort(configMap.get("z_stage_com_port"));

            setImmersionRI(Double.parseDouble(configMap.get("refractive_index").get(0)));
            setOpmAngle(Double.parseDouble(configMap.get("opm_angle").get(0)));
            setMagnification(Double.parseDouble(configMap.get("magnification").get(0)));

        } catch (Exception ex) {
            deviceManagerLogger.warning(ex.toString());
        }
    }

    public void serializeDeviceSettings(String savepath) throws IOException {
        XMLEncoder e = new XMLEncoder(new BufferedOutputStream(new FileOutputStream(savepath)));
        e.writeObject(this);
        e.close();
    }

    // =========================================================================
    // Logging / inspection
    // =========================================================================

    /** Deep log of current hardware state to ensure traceability. */
    public void logSystemState() {
        deviceManagerLogger.info("--- OPM HARDWARE SNAPSHOT ---");
        deviceManagerLogger.info("Camera: " + dOPMCameraName);

        try {
            deviceManagerLogger.info("Live ScanMode: " + core_.getProperty(dOPMCameraName, "ScanMode"));
            deviceManagerLogger.info("Live Exposure: " + core_.getExposure() + " ms");
            deviceManagerLogger.info("Live ROI: " + core_.getROI().toString());
            deviceManagerLogger.info("Calculated Readout Time: " + getCameraReadoutTime() + " ms");
        } catch (Exception e) {
            deviceManagerLogger.warning("Could not pull live camera state: " + e.getMessage());
        }

        deviceManagerLogger.info("Scan Type: " + scanType);
        deviceManagerLogger.info("XY Stage: " + xyStageName + " [Axis: " + xyStageScanAxis + "]");
        deviceManagerLogger.info("XY Trigger Dist: " + xyStageTriggerDistance + " um");
        deviceManagerLogger.info("XY Target Scan Speed: " + xyStageCurrentScanSpeed + " mm/s");
        deviceManagerLogger.info("Mirror Trigger Dist: " + mirrorTriggerDistance + " um");
        deviceManagerLogger.info("Mirror Target Scan Speed: " + mirrorStageCurrentScanSpeed + " mm/s");
        deviceManagerLogger.info("------------------------------");
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    private boolean checkInDeviceList(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        List<String> deviceNames = new ArrayList<>(Arrays.asList(deviceName));
        return checkInDeviceList(deviceNames);
    }

    private boolean checkInDeviceList(List<String> deviceNames) {
        if (deviceNames == null) {
            return false;
        }

        ArrayList<String> allDevices = new ArrayList<>(Arrays.asList(getDeviceList().toArray()));

        for (String name : deviceNames) {
            if (!allDevices.contains(name)) {
                JOptionPane.showMessageDialog(null, name + " does not exist in MicroManager config.");
                return false;
            }
        }
        return true;
    }

    private String getPortProperty(String deviceName) {
        try {
            return core_.getProperty(deviceName, "Port");
        } catch (Exception e) {
            return "";
        }
    }

    // =========================================================================
    // Acquisition-derived scan-speed updates
    // =========================================================================

    public void updateCurrentScanSpeedsDuringAcq() {
        updateMaxGlobalTriggeredXyScanSpeed();
        upateMaxGlobalTriggeredMirrorScanSpeed();
        updateMaxTriggeredXyScanSpeed();
        updateMaxTriggeredMirrorScanSpeed();

        if (getUseMaxScanSpeedForMirror()) {
            setMirrorStageCurrentScanSpeed(getMaxTriggeredMirrorScanSpeed());
        } else {
            setMirrorStageCurrentScanSpeed(getMirrorStageGlobalScanSpeed());
        }

        if (getUseMaxScanSpeedForXyStage()) {
            setXyStageCurrentScanSpeed(getMaxTriggeredXyScanSpeed());
        } else {
            setXyStageCurrentScanSpeed(getXyStageGlobalScanSpeed());
        }
    }

    private double getMaxExposureInAcq() throws Exception {
        List<ChannelSpec> acqChannels = mm_.getAcquisitionManager().getAcquisitionSettings().channels();
        double maxExposure = 0;

        for (ChannelSpec cs : acqChannels) {
            if (cs.exposure() > maxExposure) {
                maxExposure = cs.exposure();
            }
        }

        return maxExposure;
    }

    /** Uses MDA exposure settings if configured, otherwise falls back to live Core exposure. */
    public void updateMaxTriggeredXyScanSpeed() {
        try {
            double mdaExp = getMaxExposureInAcq();
            double targetExp = (mdaExp > 0) ? mdaExp : core_.getExposure();
            maxTriggeredXyScanSpeed = calculateMaxTriggeredXyScanSpeed(targetExp);
        } catch (Exception e) {
            try {
                maxTriggeredXyScanSpeed = calculateMaxTriggeredXyScanSpeed(core_.getExposure());
            } catch (Exception ignored) {}
        }
    }

    public void updateMaxTriggeredMirrorScanSpeed() {
        try {
            double mdaExp = getMaxExposureInAcq();
            double targetExp = (mdaExp > 0) ? mdaExp : core_.getExposure();
            maxTriggeredMirrorScanSpeed = calculateMaxTriggeredMirrorScanSpeed(targetExp);
        } catch (Exception e) {
            try {
                maxTriggeredMirrorScanSpeed = calculateMaxTriggeredMirrorScanSpeed(core_.getExposure());
            } catch (Exception ignored) {}
        }
    }

    public void updateMaxGlobalTriggeredXyScanSpeed() {
        try {
            maxGlobalTriggeredXyScanSpeed = calculateMaxTriggeredXyScanSpeed(getMaxExposureInAcq());
        } catch (Exception e) {}
    }

    public void upateMaxGlobalTriggeredMirrorScanSpeed() {
        try {
            maxGlobalTriggeredMirrorScanSpeed = calculateMaxTriggeredMirrorScanSpeed(getMaxExposureInAcq());
        } catch (Exception e) {}
    }

    // =========================================================================
    // Camera timing / speed calculations
    // =========================================================================

    public double getCameraReadoutTime() throws Exception {
        return getCameraReadoutTime(core_.getExposure());
    }

    public double getCameraReadoutTime(double exposureMs) throws Exception {
        int trigger = getTriggerMode();
        Rectangle roi;

        try {
            roi = core_.getROI();
        } catch (Exception e) {
            roi = getFrameSize();
        }

        final double oneHMs = 4.867647 * 1e-3;
        final double Vn = roi.height;
        final double exp2Ms = exposureMs - 3.029411 * 1e-3;
        final double syncReadoutMinMs = (Vn + 5) * oneHMs;
        final double internalClockMinMs = (Vn + 1) * oneHMs;

        switch (trigger) {
            case TRIGGER_EXTERNAL_GLOBAL:
                return (Vn + Math.ceil(exp2Ms / oneHMs) + 4) * oneHMs;
            case TRIGGER_EXTERNAL_SYNCREADOUT:
                return Math.max(syncReadoutMinMs, exposureMs);
            case TRIGGER_START_INTERNAL_CLOCK:
                return Math.max(internalClockMinMs, exposureMs);
            default:
                return Math.max(internalClockMinMs, exposureMs);
        }
    }

    public double calculateMaxTriggeredXyScanSpeed(double exp) {
        try {
            return (getXyStageTriggerDistance() / getCameraReadoutTime(exp)) * getScanSpeedSafetyFactorXy();
        } catch (Exception e) {
            return xyStageGlobalScanSpeed;
        }
    }

    public double calculateMaxTriggeredMirrorScanSpeed(double exp) {
        try {
            return (getMirrorTriggerDistance() / getCameraReadoutTime(exp)) * getScanSpeedSafetyFactorMirror();
        } catch (Exception e) {
            return mirrorStageGlobalScanSpeed;
        }
    }

    public String getTriggerModeLabel() {
        if (triggerMode >= 0 && triggerMode < triggerModeStrings.length) {
            return triggerModeStrings[triggerMode];
        }
        return "Unknown trigger mode";
    }

    // =========================================================================
    // Current hardware state helpers
    // =========================================================================

    public int getCurrentLaserIdx() {
        int laserIdx = 0;
        try {
            int laserState = Integer.parseInt(core_.getProperty(laserBlankingDOport, "State"));
            laserIdx = (int) (Math.log(laserState + 1) / Math.log(2));
        } catch (Exception e) {
            deviceManagerLogger.severe("Couldn't get laser index: " + e.toString());
        }
        return laserIdx;
    }

    public String getCurrentLaser() {
        String laser = "";
        try {
            int laserIdx = getCurrentLaserIdx();
            if (laserLabels != null && !laserLabels.isEmpty()) {
                laser = laserLabels.get(laserIdx);
            } else {
                laser = String.valueOf(laserIdx);
            }
        } catch (Exception e) {
            deviceManagerLogger.severe("Failed to get laser info: " + e.getMessage());
        }
        return laser;
    }

    public String getCurrentFilter() {
        String filter = "";
        try {
            filter = core_.getProperty(filterDeviceName, "Label");
        } catch (Exception e) {
            deviceManagerLogger.severe("Failed to get filter info: " + e.getMessage());
        }
        return filter;
    }

    public double getCurrentLaserPower() {
        String laser = getCurrentLaser();
        double power = 0;
        String powerGroupString = String.format("%s power", laser);

        try {
            Configuration cfg = core_.getConfigGroupState(powerGroupString);
            PropertySetting setting = cfg.getSetting(0);
            power = Double.parseDouble(setting.getPropertyValue());
        } catch (Exception e) {
            deviceManagerLogger.severe("Couldn't get " + laser + " power (" + e.getMessage() + ")");
        }

        return power;
    }

    // =========================================================================
    // Geometry / optical conversions
    // =========================================================================

    public double lateralScanToMirrorNormal(double lateral) {
        return 2 * lateral * Math.sin(0.5 * getOpmAngle() * Math.PI / 180) / getImmersionRI();
    }

    public double mirrorNormaltoLateralScan(double normal) {
        return normal * getImmersionRI() / (2 * Math.sin(0.5 * getOpmAngle() * Math.PI / 180));
    }

    public double lateralScanToLabZ(double lateral) {
        return lateralScanToMirrorNormal(lateral) * Math.cos(0.5 * getOpmAngle());
    }

    public double labZtoLateralScan(double z) {
        return mirrorNormaltoLateralScan(z / Math.cos(0.5 * getOpmAngle()));
    }

    // =========================================================================
    // Generic MM property wrappers
    // =========================================================================

    public String getProperty(String device, String propety) {
        String value = "";
        try {
            value = core_.getProperty(device, propety);
        } catch (Exception e) {
            deviceManagerLogger.severe(e.getMessage());
        }
        return value;
    }

    public void setProperty(String device, String propety, String propertyValue) {
        try {
            core_.setProperty(device, propety, propertyValue);
        } catch (Exception e) {
            deviceManagerLogger.severe(e.getMessage());
        }
    }

    // =========================================================================
    // Setters with dependent recalculation
    // =========================================================================

    public void setXyStageTriggerDistance(double xyStageTriggerDistance) {
        this.xyStageTriggerDistance = xyStageTriggerDistance;
        updateMaxTriggeredXyScanSpeed();
        updateMaxGlobalTriggeredXyScanSpeed();
    }

    public void setMirrorTriggerDistance(double mirrorTriggerDistance) {
        this.mirrorTriggerDistance = mirrorTriggerDistance;
        updateMaxTriggeredMirrorScanSpeed();
        upateMaxGlobalTriggeredMirrorScanSpeed();
    }

    public void setScanSpeedSafetyFactorMirror(double f) {
        this.scanSpeedSafetyFactorMirror = f;
        updateMaxTriggeredMirrorScanSpeed();
    }

    public void setScanSpeedSafetyFactorXy(double f) {
        this.scanSpeedSafetyFactorXy = f;
        updateMaxTriggeredXyScanSpeed();
    }

    public void setTriggerMode(int m) {
        this.triggerMode = m;
        updateMaxTriggeredMirrorScanSpeed();
    }

    public void setExposureTime(double e) {
        this.exposureTime = e;
        updateMaxTriggeredMirrorScanSpeed();
        updateMaxTriggeredXyScanSpeed();
    }

    public void setFrameSize(Rectangle r) {
        this.frameSize = r;
        updateMaxTriggeredXyScanSpeed();
        updateMaxTriggeredMirrorScanSpeed();
    }

    // =========================================================================
    // Plain getters / setters
    // =========================================================================

    public double getScanSpeedSafetyFactorMirror() { return scanSpeedSafetyFactorMirror; }
    public double getScanSpeedSafetyFactorXy() { return scanSpeedSafetyFactorXy; }
    public double getMaxTriggeredXyScanSpeed() { return maxTriggeredXyScanSpeed; }
    public double getMaxTriggeredMirrorScanSpeed() { return maxTriggeredMirrorScanSpeed; }
    public double getMaxGlobalTriggeredXyScanSpeed() { return maxGlobalTriggeredXyScanSpeed; }
    public double getMaxGlobalTriggeredMirrorScanSpeed() { return maxGlobalTriggeredMirrorScanSpeed; }

    public double getMirrorScanLength() { return mirrorScanLength; }
    public void setMirrorScanLength(double l) { this.mirrorScanLength = l; }

    public double getMirrorTriggerDistance() { return mirrorTriggerDistance; }

    public double getXyStageScanLength() { return xyStageScanLength; }
    public void setXyStageScanLength(double l) { this.xyStageScanLength = l; }

    public double getXyStageTriggerDistance() { return xyStageTriggerDistance; }

    public String getXyStageScanAxis() { return xyStageScanAxis; }
    public void setXyStageScanAxis(String a) { this.xyStageScanAxis = a; }

    public int getTriggerMode() { return triggerMode; }

    public int getScanType() { return scanType; }
    public void setScanType(int t) { this.scanType = t; }

    public boolean getUseMaxScanSpeedForMirror() { return useMaxScanSpeedForMirror; }
    public void setUseMaxScanSpeedForMirror(boolean b) { this.useMaxScanSpeedForMirror = b; }

    public int[] getZ_lim() { return z_lim; }
    public void setZ_lim(int[] l) { this.z_lim = l; }

    public String getXyStageName() { return xyStageName; }
    public void setXyStageName(String n) { this.xyStageName = n; this.xyStageComPort = getPortProperty(n); }
    public void setXyStageName(List<String> l) { if (!l.isEmpty()) setXyStageName(l.get(0)); }

    public double getXyStageTravelSpeed() { return xyStageTravelSpeed; }
    public void setXyStageTravelSpeed(double s) { this.xyStageTravelSpeed = s; }

    public double getXyStageCurrentScanSpeed() { return xyStageCurrentScanSpeed; }
    public void setXyStageCurrentScanSpeed(double s) { this.xyStageCurrentScanSpeed = s; }

    public double getXyStageGlobalScanSpeed() { return xyStageGlobalScanSpeed; }
    public void setXyStageGlobalScanSpeed(double s) { this.xyStageGlobalScanSpeed = s; }

    public String getXyStageComPort() { return xyStageComPort; }
    public void setXyStageComPort(String p) { this.xyStageComPort = p; }
    public void setXyStageComPort(List<String> l) { if (!l.isEmpty()) setXyStageComPort(l.get(0)); }

    public boolean getUseMaxScanSpeedForXyStage() { return useMaxScanSpeedForXyStage; }
    public void setUseMaxScanSpeedForXyStage(boolean b) { this.useMaxScanSpeedForXyStage = b; }

    public String getMirrorStageName() { return mirrorStageName; }
    public void setMirrorStageName(String n) { this.mirrorStageName = n; }
    public void setMirrorStageName(List<String> l) { if (!l.isEmpty()) setMirrorStageName(l.get(0)); }

    public double getMirrorStageTravelSpeed() { return mirrorStageTravelSpeed; }
    public void setMirrorStageTravelSpeed(double s) { this.mirrorStageTravelSpeed = s; }

    public double getMirrorStageCurrentScanSpeed() { return mirrorStageCurrentScanSpeed; }
    public void setMirrorStageCurrentScanSpeed(double s) { this.mirrorStageCurrentScanSpeed = s; }

    public double getMirrorStageGlobalScanSpeed() { return mirrorStageGlobalScanSpeed; }
    public void setMirrorStageGlobalScanSpeed(double s) { this.mirrorStageGlobalScanSpeed = s; }

    public String getMirrorStageComPort() { return mirrorStageComPort; }
    public void setMirrorStageComPort(String p) { this.mirrorStageComPort = p; }
    public void setMirrorStageComPort(List<String> l) { if (!l.isEmpty()) setMirrorStageComPort(l.get(0)); }

    public boolean isView1Imaged() { return imageView1; }
    public void setView1Imaged(boolean b) { this.imageView1 = b; }

    public boolean isView2Imaged() { return imageView2; }
    public void setView2Imaged(boolean b) { this.imageView2 = b; }

    public double getOpmAngle() { return opmAngle; }
    public void setOpmAngle(double a) { this.opmAngle = a; }
    public void setOpmAngle(List<Double> l) { if (!l.isEmpty()) this.opmAngle = l.get(0); }

    public double getImmersionRI() { return immersionRI; }
    public void setImmersionRI(double ri) { this.immersionRI = ri; }
    public void setImmersionRI(List<Double> l) { if (!l.isEmpty()) this.immersionRI = l.get(0); }

    public double getMagnification() { return magnification; }
    public void setMagnification(double m) { this.magnification = m; }
    public void setMagnification(List<Double> l) { if (!l.isEmpty()) this.magnification = l.get(0); }

    public boolean isSaveAcquisitionLogs() { return saveAcquisitionLogs; }
    public void setSaveAcquisitionLogs(boolean b) { this.saveAcquisitionLogs = b; }

    public String getZStageName() { return zStageName; }
    public void setZStageName(String n) { this.zStageName = n; setZStageComPort(getPortProperty(n)); }
    public void setZStageName(List<String> l) { if (!l.isEmpty()) setZStageName(l.get(0)); }

    public double getzStageTravelSpeed() { return zStageTravelSpeed; }
    public void setzStageTravelSpeed(double s) { this.zStageTravelSpeed = s; }

    public String getzStageComPort() { return zStageComPort; }
    public void setZStageComPort(String p) { this.zStageComPort = p; }
    public void setZStageComPort(List<String> l) { if (!l.isEmpty()) setZStageComPort(l.get(0)); }

    public double getExposureTime() { return exposureTime; }

    public double getActualExposureTime() { return actualExposureTime; }
    public void setActualExposureTime(double e) { this.actualExposureTime = e; }

    public Rectangle getFrameSize() { return frameSize; }

    public List<String> getLaserDeviceNames() { return laserDeviceNames; }
    public void setLaserDeviceNames(List<String> l) { if (checkInDeviceList(l)) this.laserDeviceNames = l; }

    public List<String> getLaserBlankingDOLines() { return laserBlankingDOLines; }

    public void setLaserBlankingDOLines(List<String> laserBlankingDOLines) throws Exception {
        if (laserBlankingDOLines != null && checkInDeviceList(getLaserBlankingDOport())) {
            this.laserBlankingDOLines = laserBlankingDOLines;
        }
    }

    public List<String> getLaserLabels() { return laserLabels; }
    public void setLaserLabels(List<String> l) { this.laserLabels = l; }

    public String getLaserBlankingDOport() { return laserBlankingDOport; }
    public void setLaserBlankingDOport(String p) { if (checkInDeviceList(p)) this.laserBlankingDOport = p; }
    public void setLaserBlankingDOport(List<String> l) { if (!l.isEmpty()) setLaserBlankingDOport(l.get(0)); }

    public List<String> getLaserPowerAOports() { return laserPowerAOports; }
    public void setLaserPowerAOports(List<String> l) { if (checkInDeviceList(l)) this.laserPowerAOports = l; }

    public String getFilterDeviceName() { return filterDeviceName; }
    public void setFilterDeviceName(String n) { if (checkInDeviceList(n)) this.filterDeviceName = n; }
    public void setFilterDeviceName(List<String> l) { if (!l.isEmpty()) setFilterDeviceName(l.get(0)); }

    public String getdOPMCameraName() { return dOPMCameraName; }
    public void setdOPMCameraName(String n) { if (checkInDeviceList(n)) this.dOPMCameraName = n; }
    public void setdOPMCameraName(List<String> l) { if (!l.isEmpty()) setdOPMCameraName(l.get(0)); }

    public StrVector getDeviceList() { return deviceList; }
    public void setDeviceList(StrVector l) { this.deviceList = l; }

    // =========================================================================
    // Nested class
    // =========================================================================

    // Preserving original struct-like pattern for laser devices
    public class USBLaserDevice {
        private String name;
        private String wavelength;
        private String lineDO;
        private String enableGroup;
        private String powerGroup;
        private String type;

        void LaserDevice(String name, String wavelength, String lineDO) {
            this.name = name;
            this.lineDO = lineDO;
            this.wavelength = wavelength;
            this.type = "DAQ laser";
            this.powerGroup = String.format("Power %s", wavelength);
        }
    }
}