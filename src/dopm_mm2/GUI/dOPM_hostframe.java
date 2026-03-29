/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package dopm_mm2.GUI;

import java.util.logging.Logger;
import javax.swing.*;
import org.micromanager.Studio;
import mmcorej.CMMCore;
import org.micromanager.ScriptController;
import org.micromanager.data.Datastore;
import dopm_mm2.Devices.DeviceSettingsManager;
import dopm_mm2.Runnables.MDARunnable;
import dopm_mm2.util.MMStudioInstance;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import org.micromanager.propertymap.MutablePropertyMapView;

/**
 *
 * @author lnr19
 * REVISION (Layer 8.5):
 * - UI Defaults: XY Stage Scan tab and Y Stage radio button selected on startup.
 * - Safety Defaults: Fraction of Max set to 0.90.
 * - Visual Feedback: Scan speed fields transform into Indicators (gray/read-only) when Max is ticked.
 */
public class dOPM_hostframe extends javax.swing.JFrame {

    static dOPM_hostframe frame_;
    public static Studio mm_ = null;
    public CMMCore core_ = null;
     
    private static final Logger dOPM_hostframeLogger = 
            Logger.getLogger(dOPM_hostframe.class.getName());
    
    private static final Logger rootLogger = 
        Logger.getLogger("");
    private File dopm_mm2Logdir;
    private File dopm_mm2Logfile;
    
    public Thread testPIVolumeThread;
    private boolean interruptFlag;
    private Datastore datastore;
    
    // Base folder directory and other directories
    private String baseFolderDir;
    private String settingsFolderDir;
    private String dataFolderDir;
    
    private String configFilePath;
    
    private boolean saveImgToDisk;
    private boolean runnableIsRunning;
    
    // Device settings object
    private DeviceSettingsManager deviceSettings;    
    private final MutablePropertyMapView dopmUserProfile;
    
    //Other settings ?
    // ...
    
    //Various managers
    private ScriptController sc;


    public dOPM_hostframe() {        
        // singleton which contains studio and core so i dont have to inject every time
        
        mm_ = MMStudioInstance.getStudio();
        core_ = MMStudioInstance.getCore();

        frame_ = this;
        frame_.setTitle("dOPM controller for Micro-manager 2");
        frame_.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        
        dopmUserProfile = mm_.getUserProfile().getSettings(this.getClass());
                
        // set logging format for whole app
        System.setProperty(
            "java.util.logging.SimpleFormatter.format",
            "[%1$tF %1$tT %1$tL] [%4$-7s] %2$s %5$s %n");
        
        makeDirsAndLog();
        
        saveImgToDisk = true;
        
        String defaultBasedir = new File(
                System.getenv("USERPROFILE"), "dopmData").getAbsolutePath();
        
        baseFolderDir = dopmUserProfile.getString(
                "baseFolderDir", defaultBasedir);

        dataFolderDir = new File(baseFolderDir, "data").getAbsolutePath();
        settingsFolderDir = new File(baseFolderDir, "settings").getAbsolutePath();
        
        String defaultConfigPath = new File(System.getenv("USERPROFILE"), 
                "dopm_plugin/dopmDeviceConfig.json").getAbsolutePath();
        
        configFilePath = dopmUserProfile.getString(
                "configFilePath", defaultConfigPath);
                
        deviceSettings = new DeviceSettingsManager(core_);
        deviceSettings.loadSystemSettings(configFilePath);
        
        runnableIsRunning = false;
        
        initComponents();
        
        // REVISION Layer 8.5: Apply defaults for XY Stage scanning and safe speeds
        xyMaxSpeedCheckBox.setSelected(true);
        mirrorMaxSpeedCheckBox.setSelected(true);
        fracOfMaxXyField.setText("0.90");
        fracOfMaxMirrorField.setText("0.90");
        
        deviceSettings.setUseMaxScanSpeedForXyStage(true);
        deviceSettings.setUseMaxScanSpeedForMirror(true);
        deviceSettings.setScanSpeedSafetyFactorXy(0.90);
        deviceSettings.setScanSpeedSafetyFactorMirror(0.90);

        // DEFAULT UI VIEW: Select XY Stage Scan tab (index 1) and Y Stage radio button
        jTabbedPane1.setSelectedIndex(1); 
        yScanRadioButton.setSelected(true);
        deviceSettings.setScanType(DeviceSettingsManager.YSTAGE_SCAN);
        
        updateScanSpeedIndicators(); // Initialize visual states (Indicators vs Inputs)
        
        try {
            ImageIcon img = new ImageIcon(".\\dopm_icon.png");
            frame_.setIconImage(img.getImage());

        } catch (Exception e){
            dOPM_hostframeLogger.warning("Failed to find dOPM_MM2 icon: " 
                    + e.getMessage());
        }
        
        dOPM_hostframeLogger.info("Initialised dOPM_MM2 hostframe");

    }

    /** REVISION Layer 8: Updates UI text boxes to show calculated values 
     * and toggles their "Indicator" (read-only) status.
     */
    private void updateScanSpeedIndicators() {
        deviceSettings.updateCurrentScanSpeedsDuringAcq();

        // XY Stage Visual Logic
        if (xyMaxSpeedCheckBox.isSelected()) {
            xyScanSpeedField.setText(String.format("%.4f", deviceSettings.getXyStageCurrentScanSpeed()));
            xyScanSpeedField.setEditable(false);
            xyScanSpeedField.setBackground(new java.awt.Color(240, 240, 240));
        } else {
            xyScanSpeedField.setEditable(true);
            xyScanSpeedField.setBackground(java.awt.Color.WHITE);
        }

        // Mirror Visual Logic
        if (mirrorMaxSpeedCheckBox.isSelected()) {
            mirrorScanSpeedField.setText(String.format("%.4f", deviceSettings.getMirrorStageCurrentScanSpeed()));
            mirrorScanSpeedField.setEditable(false);
            mirrorScanSpeedField.setBackground(new java.awt.Color(240, 240, 240));
        } else {
            mirrorScanSpeedField.setEditable(true);
            mirrorScanSpeedField.setBackground(java.awt.Color.WHITE);
        }
    }
    
    private int makeDirsAndLog(){
        try { 
            LocalDateTime date = LocalDateTime.now(); // Create a date object
            DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern(
                    "yyyyMMddhhmmss");

            String formattedDate = date.format(myFormatObj);
            
            String appdata = System.getenv("LOCALAPPDATA");
            dopm_mm2Logdir = new File(appdata, "Micro-Manager/dopmLogs");
            dopm_mm2Logdir.mkdir();
            dopm_mm2Logfile = new File(dopm_mm2Logdir, String.format(
                    "dopmRootLog%s.log", formattedDate));
            
            FileHandler fh = new FileHandler(dopm_mm2Logfile.getAbsolutePath());
 
            rootLogger.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();  
            fh.setFormatter(formatter);  
        } catch (IOException ioe){
            rootLogger.severe("Failed to create log with " + ioe.getMessage());
            return 1;
        } catch (SecurityException se){
            rootLogger.severe("Failed to create log file " + se.getMessage());
            return 1;
        }
        return 0;
    }
        
    private Object[] getLaserChannelOptions() throws Exception{
        throw new Exception("Not implemented");
    }
    
    private void getPossibleDeviceStates(){
        // TODO delete
    }
    
    private void openDeviceConfig(){
        
    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        buttonGroup1 = new javax.swing.ButtonGroup();
        fileSettingsPanel = new javax.swing.JPanel();
        saveDirectoryLabel = new javax.swing.JLabel();
        saveDirectoryField = new javax.swing.JTextField();
        browseDirectoryField = new javax.swing.JButton();
        saveToDiskCheckBox = new javax.swing.JCheckBox();
        startButton = new javax.swing.JButton();
        stopButton = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        debugTextArea = new javax.swing.JTextArea();
        jProgressBar = new javax.swing.JProgressBar();
        snapTestButton = new javax.swing.JButton();
        jPanel1 = new javax.swing.JPanel();
        jTabbedPane1 = new javax.swing.JTabbedPane();
        mirrorScanSettingsPanel = new javax.swing.JPanel();
        mirrorScanLengthField = new javax.swing.JTextField();
        mirrorScanSpeedField = new javax.swing.JTextField();
        mirrorScanIntervalField = new javax.swing.JTextField();
        mirrorMaxSpeedCheckBox = new javax.swing.JCheckBox();
        fracOfMaxMirrorField = new javax.swing.JTextField();
        fracOfMaxMirrorLabel = new javax.swing.JLabel();
        scanLengthLabel1 = new javax.swing.JLabel();
        scanSpeedLabel1 = new javax.swing.JLabel();
        scanIntervalLabel1 = new javax.swing.JLabel();
        xyStageScanSettingsPanel = new javax.swing.JPanel();
        xyScanLengthField = new javax.swing.JTextField();
        xyScanSpeedField = new javax.swing.JTextField();
        xyScanIntervalField = new javax.swing.JTextField();
        xyMaxSpeedCheckBox = new javax.swing.JCheckBox();
        scanLengthLabel = new javax.swing.JLabel();
        scanSpeedLabel = new javax.swing.JLabel();
        scanIntervalLabel = new javax.swing.JLabel();
        fracOfMaxXyLabel = new javax.swing.JLabel();
        fracOfMaxXyField = new javax.swing.JTextField();
        jPanel2 = new javax.swing.JPanel();
        triggerModeLabel = new javax.swing.JLabel();
        triggerModeComboBox = new javax.swing.JComboBox<>();
        mirrorScanRadioButton = new javax.swing.JRadioButton();
        xScanRadioButton = new javax.swing.JRadioButton();
        yScanRadioButton = new javax.swing.JRadioButton();
        scanStageTypeLabel = new javax.swing.JLabel();
        viewsLabel = new javax.swing.JLabel();
        view1CheckBox = new javax.swing.JCheckBox();
        view2CheckBox = new javax.swing.JCheckBox();
        jMenuBar1 = new javax.swing.JMenuBar();
        jMenu1 = new javax.swing.JMenu();
        openDeviceConfigMenuItem = new javax.swing.JMenuItem();
        clearLogsMenuItem = new javax.swing.JMenuItem();
        jMenu2 = new javax.swing.JMenu();

        fileSettingsPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("File settings"));

        saveDirectoryLabel.setText("Save directory");

        saveDirectoryField.setText(getBaseFolderDir());
        saveDirectoryField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveDirectoryFieldActionPerformed(evt);
            }
        });

        browseDirectoryField.setText("Browse");
        browseDirectoryField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                browseDirectoryFieldActionPerformed(evt);
            }
        });

        saveToDiskCheckBox.setText("Save to disk");
        saveToDiskCheckBox.setAlignmentX(1.0F);
        saveToDiskCheckBox.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
        saveToDiskCheckBox.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        saveToDiskCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveToDiskCheckBoxActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout fileSettingsPanelLayout = new javax.swing.GroupLayout(fileSettingsPanel);
        fileSettingsPanel.setLayout(fileSettingsPanelLayout);
        fileSettingsPanelLayout.setHorizontalGroup(
            fileSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(fileSettingsPanelLayout.createSequentialGroup()
                .addGroup(fileSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(fileSettingsPanelLayout.createSequentialGroup()
                        .addGap(7, 7, 7)
                        .addComponent(saveDirectoryLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(saveDirectoryField, javax.swing.GroupLayout.PREFERRED_SIZE, 222, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(browseDirectoryField))
                    .addGroup(fileSettingsPanelLayout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(saveToDiskCheckBox)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        fileSettingsPanelLayout.setVerticalGroup(
            fileSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(fileSettingsPanelLayout.createSequentialGroup()
                .addGroup(fileSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(saveDirectoryLabel)
                    .addComponent(saveDirectoryField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(browseDirectoryField))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(saveToDiskCheckBox)
                .addContainerGap())
        );

        startButton.setText("Start");
        startButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startButtonActionPerformed(evt);
            }
        });

        stopButton.setText("Stop");
        stopButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopButtonActionPerformed(evt);
            }
        });

        debugTextArea.setEditable(false);
        debugTextArea.setColumns(20);
        debugTextArea.setRows(5);
        jScrollPane1.setViewportView(debugTextArea);

        snapTestButton.setText("snap test");
        snapTestButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                snapTestButtonActionPerformed(evt);
            }
        });

        jPanel1.setBorder(javax.swing.BorderFactory.createTitledBorder("Scan settings"));

        jTabbedPane1.setBorder(javax.swing.BorderFactory.createTitledBorder(""));
        jTabbedPane1.setToolTipText("");
        jTabbedPane1.setName("Scan settings tabbedPane"); // NOI18N

        mirrorScanSettingsPanel.setPreferredSize(new java.awt.Dimension(411, 90));

        mirrorScanLengthField.setText(String.format("%.1f", deviceSettings.getMirrorScanLength()));
        mirrorScanLengthField.setInputVerifier(new typeVerifierDouble());
        mirrorScanLengthField.setPreferredSize(new java.awt.Dimension(100, 22));
        mirrorScanLengthField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mirrorScanLengthFieldActionPerformed(evt);
            }
        });

        mirrorScanSpeedField.setText(String.format("%.4f", deviceSettings.getMirrorStageGlobalScanSpeed()));
        mirrorScanSpeedField.setActionCommand("<Not Set>");
        mirrorScanSpeedField.setInputVerifier(new typeVerifierDouble());
        mirrorScanSpeedField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mirrorScanSpeedFieldActionPerformed(evt);
            }
        });

        mirrorScanIntervalField.setText(String.format("%.2f", deviceSettings.getMirrorTriggerDistance()));
        mirrorScanIntervalField.setInputVerifier(new typeVerifierDouble());
        mirrorScanIntervalField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mirrorScanIntervalFieldActionPerformed(evt);
            }
        });

        mirrorMaxSpeedCheckBox.setText("Max");
        mirrorMaxSpeedCheckBox.setToolTipText("Tick to use fastest possible scanning speed for given settings");
        mirrorMaxSpeedCheckBox.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        mirrorMaxSpeedCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mirrorMaxSpeedCheckBoxActionPerformed(evt);
            }
        });

        fracOfMaxMirrorField.setText(String.format("%.2f",deviceSettings.getScanSpeedSafetyFactorMirror()));
        fracOfMaxMirrorField.setToolTipText("The percentage of the maximum theoretical scan speed, 95% is recommended");
        fracOfMaxMirrorField.setInputVerifier(new pcVerifier());
        fracOfMaxMirrorField.setPreferredSize(new java.awt.Dimension(100, 22));
        fracOfMaxMirrorField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fracOfMaxMirrorFieldActionPerformed(evt);
            }
        });

        fracOfMaxMirrorLabel.setText("Fraction of Max");

        scanLengthLabel1.setText("Z' scan length (µm)");
        scanLengthLabel1.setToolTipText("Scan length in z' (normal to imaged plane direction)");

        scanSpeedLabel1.setText("Scan speed (µm/ms)");
        scanSpeedLabel1.setToolTipText("Set a \"global\" scan speed for all channels, ticking Max will ignore this. Speed is physical PI stage lateral scan speed.");

        scanIntervalLabel1.setText("Z' scan interval (µm)");

        javax.swing.GroupLayout mirrorScanSettingsPanelLayout = new javax.swing.GroupLayout(mirrorScanSettingsPanel);
        mirrorScanSettingsPanel.setLayout(mirrorScanSettingsPanelLayout);
        mirrorScanSettingsPanelLayout.setHorizontalGroup(
            mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(mirrorScanSettingsPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(mirrorScanSettingsPanelLayout.createSequentialGroup()
                            .addComponent(scanSpeedLabel1)
                            .addGap(7, 7, 7))
                        .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, mirrorScanSettingsPanelLayout.createSequentialGroup()
                            .addComponent(scanLengthLabel1)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, mirrorScanSettingsPanelLayout.createSequentialGroup()
                        .addComponent(scanIntervalLabel1)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)))
                .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(mirrorScanIntervalField, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(mirrorScanLengthField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(mirrorScanSettingsPanelLayout.createSequentialGroup()
                        .addComponent(mirrorScanSpeedField, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(mirrorMaxSpeedCheckBox, javax.swing.GroupLayout.PREFERRED_SIZE, 46, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(fracOfMaxMirrorLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(fracOfMaxMirrorField, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(11, Short.MAX_VALUE))
        );
        mirrorScanSettingsPanelLayout.setVerticalGroup(
            mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(mirrorScanSettingsPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(mirrorScanLengthField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scanLengthLabel1))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(mirrorMaxSpeedCheckBox)
                        .addComponent(fracOfMaxMirrorField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(fracOfMaxMirrorLabel))
                    .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(mirrorScanSpeedField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(scanSpeedLabel1)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(mirrorScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(scanIntervalLabel1)
                    .addComponent(mirrorScanIntervalField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jTabbedPane1.addTab("Mirror scan", mirrorScanSettingsPanel);

        xyScanLengthField.setText(String.format("%.1f", deviceSettings.getXyStageScanLength()));
        xyScanLengthField.setInputVerifier(new typeVerifierDouble());
        xyScanLengthField.setPreferredSize(new java.awt.Dimension(100, 22));
        xyScanLengthField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xyScanLengthFieldActionPerformed(evt);
            }
        });

        xyScanSpeedField.setText(String.format("%.4f", deviceSettings.getXyStageCurrentScanSpeed()));
        xyScanSpeedField.setActionCommand("<Not Set>");
        xyScanSpeedField.setInputVerifier(new typeVerifierDouble());
        xyScanSpeedField.setPreferredSize(new java.awt.Dimension(100, 22));
        xyScanSpeedField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xyScanSpeedFieldActionPerformed(evt);
            }
        });

        xyScanIntervalField.setText(String.format("%.2f", deviceSettings.getXyStageTriggerDistance()));
        xyScanIntervalField.setInputVerifier(new typeVerifierDouble());
        xyScanIntervalField.setPreferredSize(new java.awt.Dimension(100, 22));
        xyScanIntervalField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xyScanIntervalFieldActionPerformed(evt);
            }
        });

        xyMaxSpeedCheckBox.setText("Max");
        xyMaxSpeedCheckBox.setToolTipText("Tick to use fastest possible scanning speed for given settings");
        xyMaxSpeedCheckBox.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        xyMaxSpeedCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xyMaxSpeedCheckBoxActionPerformed(evt);
            }
        });

        scanLengthLabel.setText("Scan length (µm)");

        scanSpeedLabel.setText("Scan speed (µm/ms)");
        scanSpeedLabel.setToolTipText("Set a \"global\" scan speed for all channels, ticking Max will ignore this");

        scanIntervalLabel.setText("Scan interval (µm)");

        fracOfMaxXyLabel.setText("Fraction of Max");

        fracOfMaxXyField.setText(String.format("%.2f",deviceSettings.getScanSpeedSafetyFactorXy()));
        fracOfMaxXyField.setToolTipText("The percentage of the maximum theoretical scan speed, 95% is recommended");
        fracOfMaxXyField.setInputVerifier(new pcVerifier());
        fracOfMaxXyField.setPreferredSize(new java.awt.Dimension(100, 22));
        fracOfMaxXyField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fracOfMaxXyFieldActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );

        javax.swing.GroupLayout xyStageScanSettingsPanelLayout = new javax.swing.GroupLayout(xyStageScanSettingsPanel);
        xyStageScanSettingsPanel.setLayout(xyStageScanSettingsPanelLayout);
        xyStageScanSettingsPanelLayout.setHorizontalGroup(
            xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, xyStageScanSettingsPanelLayout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(scanIntervalLabel)
                        .addComponent(scanLengthLabel))
                    .addComponent(scanSpeedLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(xyScanIntervalField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(xyScanLengthField, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(xyStageScanSettingsPanelLayout.createSequentialGroup()
                        .addComponent(xyScanSpeedField, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(xyMaxSpeedCheckBox)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(fracOfMaxXyLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(fracOfMaxXyField, javax.swing.GroupLayout.PREFERRED_SIZE, 80, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        xyStageScanSettingsPanelLayout.setVerticalGroup(
            xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(xyStageScanSettingsPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(xyScanLengthField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scanLengthLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(xyScanSpeedField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(xyMaxSpeedCheckBox)
                        .addComponent(fracOfMaxXyLabel)
                        .addComponent(fracOfMaxXyField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(scanSpeedLabel))
                    .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(xyStageScanSettingsPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(xyScanIntervalField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scanIntervalLabel))
                .addContainerGap(12, Short.MAX_VALUE))
        );

        jTabbedPane1.addTab("XY Stage scan", xyStageScanSettingsPanel);

        triggerModeLabel.setText("Trigger mode");

        triggerModeComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "External trigger (global reset)", "External trigger (synchronous readout)", "Untriggered (start trigger + internal clock)" }));
        triggerModeComboBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                triggerModeComboBoxActionPerformed(evt);
            }
        });

        buttonGroup1.add(mirrorScanRadioButton);
        mirrorScanRadioButton.setSelected(true);
        mirrorScanRadioButton.setText("Mirror");
        mirrorScanRadioButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mirrorScanRadioButtonActionPerformed(evt);
            }
        });

        buttonGroup1.add(xScanRadioButton);
        xScanRadioButton.setText("X stage");
        xScanRadioButton.setToolTipText("Unused, X scan is unlikely to be used");
        xScanRadioButton.setEnabled(false);
        xScanRadioButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xScanRadioButtonActionPerformed(evt);
            }
        });

        buttonGroup1.add(yScanRadioButton);
        yScanRadioButton.setText("Y Stage");
        yScanRadioButton.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
        yScanRadioButton.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
        yScanRadioButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                yScanRadioButtonActionPerformed(evt);
            }
        });

        scanStageTypeLabel.setText("Scan type");

        viewsLabel.setText("Views acquired");

        view1CheckBox.setSelected(deviceSettings.isView1Imaged());
        view1CheckBox.setText("View 1");
        view1CheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                view1CheckBoxActionPerformed(evt);
            }
        });

        view2CheckBox.setSelected(deviceSettings.isView2Imaged());
        view2CheckBox.setText("View 2");
        view2CheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                view2CheckBoxActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(viewsLabel, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(scanStageTypeLabel, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(triggerModeLabel, javax.swing.GroupLayout.Alignment.TRAILING))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(triggerModeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(jPanel1Layout.createSequentialGroup()
                                .addComponent(mirrorScanRadioButton)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(yScanRadioButton)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(xScanRadioButton))
                            .addGroup(jPanel1Layout.createSequentialGroup()
                                .addComponent(view1CheckBox)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(view2CheckBox)))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(jTabbedPane1, javax.swing.GroupLayout.Alignment.TRAILING))
                .addContainerGap())
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jTabbedPane1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(triggerModeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(triggerModeLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(mirrorScanRadioButton)
                    .addComponent(scanStageTypeLabel)
                    .addComponent(yScanRadioButton)
                    .addComponent(xScanRadioButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(viewsLabel)
                        .addComponent(view1CheckBox))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addComponent(view2CheckBox)
                        .addContainerGap())))
        );

        jTabbedPane1.getAccessibleContext().setAccessibleName("Mirror scan");
        jTabbedPane1.getAccessibleContext().setAccessibleDescription("");

        jMenu1.setText("File");

        openDeviceConfigMenuItem.setText("Open Device Config");
        openDeviceConfigMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openDeviceConfigMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(openDeviceConfigMenuItem);

        clearLogsMenuItem.setText("Clear dOPM logs");
        clearLogsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearLogsMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(clearLogsMenuItem);

        jMenuBar1.add(jMenu1);

        jMenu2.setText("Edit");
        jMenuBar1.add(jMenu2);

        setJMenuBar(jMenuBar1);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(startButton)
                            .addComponent(stopButton)
                            .addComponent(snapTestButton))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jProgressBar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(jScrollPane1)))
                    .addComponent(fileSettingsPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(fileSettingsPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 69, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jProgressBar, javax.swing.GroupLayout.PREFERRED_SIZE, 13, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(startButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(stopButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(snapTestButton)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

   
    
    private void triggerModeComboBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_triggerModeComboBoxActionPerformed
        deviceSettings.setTriggerMode(triggerModeComboBox.getSelectedIndex());
        updateScanSpeedIndicators();
    }//GEN-LAST:event_triggerModeComboBoxActionPerformed

    private void browseDirectoryFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_browseDirectoryFieldActionPerformed
        JFileChooser fc = new JFileChooser(baseFolderDir);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int returnVal = fc.showDialog(this, "Select");
        
        if (returnVal == JFileChooser.APPROVE_OPTION){
            setBaseFolderDir(fc.getSelectedFile().getAbsolutePath());
            // and update the GUI too
            saveDirectoryField.setText(baseFolderDir);
        }
    }//GEN-LAST:event_browseDirectoryFieldActionPerformed

    private void stopButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_stopButtonActionPerformed
        if(runnableIsRunning) this.setInterruptFlag(true);
        else this.setInterruptFlag(false);
    }//GEN-LAST:event_stopButtonActionPerformed

    private void startButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startButtonActionPerformed
        // run the runnable        
        int scanType = deviceSettings.getScanType();
        
        // Method that uses the MDA sequencer: // // // // // // // // // // //
        Runnable volumeAcqRunnable = new MDARunnable(this, scanType);
        Thread volumeAcqThread = new Thread(volumeAcqRunnable);
        volumeAcqThread.start();
        setRunnableIsRunning(true);
        
    }//GEN-LAST:event_startButtonActionPerformed

    private void saveToDiskCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveToDiskCheckBoxActionPerformed
        if (saveToDiskCheckBox.isSelected()){
            setSaveImgToDisk(true);
        } else {
            setSaveImgToDisk(false);
        }
    }//GEN-LAST:event_saveToDiskCheckBoxActionPerformed

    
    private void saveDirectoryFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveDirectoryFieldActionPerformed
        setBaseFolderDir(saveDirectoryField.getText());
    }//GEN-LAST:event_saveDirectoryFieldActionPerformed

    private void snapTestButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_snapTestButtonActionPerformed
        // Placeholder for original test functionality
    }//GEN-LAST:event_snapTestButtonActionPerformed

    private void openDeviceConfigMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openDeviceConfigMenuItemActionPerformed
        JFileChooser fc = new JFileChooser(baseFolderDir);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int returnVal = fc.showDialog(this, "Select");

        if (returnVal == JFileChooser.APPROVE_OPTION){
            String configFile = fc.getSelectedFile().getAbsolutePath();
            setConfigFilePath(configFile);
            deviceSettings.loadSystemSettings(configFile);
            updateScanSpeedIndicators();
        }   
    }//GEN-LAST:event_openDeviceConfigMenuItemActionPerformed


    private void mirrorScanRadioButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mirrorScanRadioButtonActionPerformed
        if (mirrorScanRadioButton.isSelected()){
            deviceSettings.setScanType(DeviceSettingsManager.MIRROR_SCAN);
        }
    }//GEN-LAST:event_mirrorScanRadioButtonActionPerformed

    private void xScanRadioButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xScanRadioButtonActionPerformed
        if (xScanRadioButton.isSelected()){
            deviceSettings.setScanType(DeviceSettingsManager.XSTAGE_SCAN);
        }
    }//GEN-LAST:event_xScanRadioButtonActionPerformed

    private void yScanRadioButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_yScanRadioButtonActionPerformed
        if (yScanRadioButton.isSelected()){
            deviceSettings.setScanType(DeviceSettingsManager.YSTAGE_SCAN);
        }
    }//GEN-LAST:event_yScanRadioButtonActionPerformed

    private void view2CheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_view2CheckBoxActionPerformed
        if (view2CheckBox.isSelected()){
            deviceSettings.setView2Imaged(true);
        } else deviceSettings.setView2Imaged(false);
        
    }//GEN-LAST:event_view2CheckBoxActionPerformed

    private void view1CheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_view1CheckBoxActionPerformed
        if (view1CheckBox.isSelected()){
            deviceSettings.setView1Imaged(true);
        } else deviceSettings.setView1Imaged(false);
    }//GEN-LAST:event_view1CheckBoxActionPerformed

    private void clearLogsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clearLogsMenuItemActionPerformed
        try {
            File[] files = dopm_mm2Logdir.listFiles((dir, name) -> 
                    name.endsWith(".log") & !name.equals(dopm_mm2Logfile.getName()));
            for(File file : files){
                file.delete();
            }
        } catch (SecurityException e){
            dOPM_hostframeLogger.severe("Failed to delete logs "
                    + "(access denied probably) with: " + e.getMessage());
            throw e;
        } catch (Exception e){
            dOPM_hostframeLogger.severe("Failed to delete logs with: " + e.getMessage());
            throw e;
        }
    }//GEN-LAST:event_clearLogsMenuItemActionPerformed

    private void mirrorMaxSpeedCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mirrorMaxSpeedCheckBoxActionPerformed
        boolean isSelected = mirrorMaxSpeedCheckBox.isSelected();
        deviceSettings.setUseMaxScanSpeedForMirror(isSelected);
        updateScanSpeedIndicators();
    }//GEN-LAST:event_mirrorMaxSpeedCheckBoxActionPerformed

    private void mirrorScanIntervalFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mirrorScanIntervalFieldActionPerformed
        double scanInterval = Double.parseDouble(mirrorScanIntervalField.getText());
        dOPM_hostframeLogger.info("parsing scan interval as " + scanInterval);
        deviceSettings.setMirrorTriggerDistance(scanInterval);
        updateScanSpeedIndicators();
    }//GEN-LAST:event_mirrorScanIntervalFieldActionPerformed

    private void mirrorScanSpeedFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mirrorScanSpeedFieldActionPerformed
        double scanSpeedInput = Double.parseDouble(mirrorScanSpeedField.getText());
        deviceSettings.setMirrorStageGlobalScanSpeed(scanSpeedInput);
        mirrorScanSpeedField.setText(String.format("%.4f", deviceSettings.getMirrorStageGlobalScanSpeed()));
    }//GEN-LAST:event_mirrorScanSpeedFieldActionPerformed

    private void mirrorScanLengthFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mirrorScanLengthFieldActionPerformed
        double scanLength = Double.parseDouble(mirrorScanLengthField.getText());
        dOPM_hostframeLogger.info("parsing scan length as " + scanLength);
        deviceSettings.setMirrorScanLength(scanLength);
    }//GEN-LAST:event_mirrorScanLengthFieldActionPerformed

    private void fracOfMaxMirrorFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fracOfMaxMirrorFieldActionPerformed
        double mirrorSafetyFactor = Double.parseDouble(fracOfMaxMirrorField.getText());
        deviceSettings.setScanSpeedSafetyFactorMirror(mirrorSafetyFactor);
        updateScanSpeedIndicators();
    }//GEN-LAST:event_fracOfMaxMirrorFieldActionPerformed

    private void fracOfMaxXyFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fracOfMaxXyFieldActionPerformed
        double xySafetyFactor = Double.parseDouble(fracOfMaxXyField.getText());
        deviceSettings.setScanSpeedSafetyFactorXy(xySafetyFactor);
        updateScanSpeedIndicators();
    }//GEN-LAST:event_fracOfMaxXyFieldActionPerformed

    private void xyMaxSpeedCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xyMaxSpeedCheckBoxActionPerformed
        boolean isSelected = xyMaxSpeedCheckBox.isSelected();
        deviceSettings.setUseMaxScanSpeedForXyStage(isSelected);
        updateScanSpeedIndicators();
    }//GEN-LAST:event_xyMaxSpeedCheckBoxActionPerformed

    private void xyScanIntervalFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xyScanIntervalFieldActionPerformed
        deviceSettings.setXyStageTriggerDistance(Double.parseDouble(xyScanIntervalField.getText()));
        updateScanSpeedIndicators();
    }//GEN-LAST:event_xyScanIntervalFieldActionPerformed

    private void xyScanSpeedFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xyScanSpeedFieldActionPerformed
        double scanSpeedInput = Double.parseDouble(xyScanSpeedField.getText());
        deviceSettings.setXyStageGlobalScanSpeed(scanSpeedInput);
        xyScanSpeedField.setText(String.format("%.4f", deviceSettings.getXyStageGlobalScanSpeed()));
    }//GEN-LAST:event_xyScanSpeedFieldActionPerformed

    private void xyScanLengthFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xyScanLengthFieldActionPerformed
        double scanLength = Double.parseDouble(xyScanLengthField.getText());
        deviceSettings.setXyStageScanLength(scanLength);
    }//GEN-LAST:event_xyScanLengthFieldActionPerformed

    
    class pcVerifier extends InputVerifier {
        @Override
        public boolean verify(JComponent input){
            JTextField tf = (JTextField) input;
            try {
                Double pcInput = Double.valueOf(tf.getText());
                return (pcInput >= 0 && pcInput <= 1);
            } catch (Exception e) {
                dOPM_hostframeLogger.warning(e.getMessage());
                return false;
            }
        }
    }
    
    class typeVerifierDouble extends InputVerifier {
        @Override
        public boolean verify(JComponent input) {
            JTextField tf = (JTextField) input;
            try {
                Double.parseDouble(tf.getText());
                return true;
            } catch (Exception e) {
                dOPM_hostframeLogger.warning(e.getMessage());
                return false;
            }
        }
    } 

    class typeVerifier extends InputVerifier {
        String type;
        private typeVerifier(String type){
            this.type = type;
        }
        @Override
        public boolean verify(JComponent input) {
            JTextField tf = (JTextField) input;
            try {
                switch (type){
                case "double":
                    Double.parseDouble(tf.getText());
                    return true;
                case "int":
                    Integer.parseInt(tf.getText());
                    return true;
                case "string":
                    tf.getText(); // I mean, this is always a string.
                    return true;
                }
            } catch (Exception e) {
                dOPM_hostframeLogger.warning(String.format("invalid input for type %s. %s",
                        type, e.getMessage()));
                return false;
            }
            return false;
        }
    }

    public boolean isSaveImgToDisk() {
        return saveImgToDisk;
    }

    public void setSaveImgToDisk(boolean saveImgToDisk) {
        this.saveImgToDisk = saveImgToDisk;
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    public void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath;
        dopmUserProfile.putString("configFilePath", configFilePath);
    }

    public boolean isRunnableIsRunning() {
        return runnableIsRunning;
    }

    public void setRunnableIsRunning(boolean runnableIsRunning) {
        this.runnableIsRunning = runnableIsRunning;
    }
    
    
    public boolean getInterruptFlag() {
        return interruptFlag;
    }

    public void setInterruptFlag(boolean interruptFlag) {
        this.interruptFlag = interruptFlag;
    } 
    
    public String getBaseFolderDir() {
        return baseFolderDir;
    }

    public void setBaseFolderDir(String baseFolderDir) {
        this.baseFolderDir = baseFolderDir;
        dopmUserProfile.putString(
                "baseFolderDir", baseFolderDir);
        setSettingsFolderDir(
                new File(baseFolderDir, "settings").getAbsolutePath());
        setDataFolderDir(new File(baseFolderDir, "data").getAbsolutePath());

    }

    public String getSettingsFolderDir() {
        return settingsFolderDir;
    }

    public void setSettingsFolderDir(String settingsFolderDir) {
        this.settingsFolderDir = settingsFolderDir;
    }

    public String getDataFolderDir() {
        return dataFolderDir;
    }

    public void setDataFolderDir(String dataFolderDir) {
        this.dataFolderDir = dataFolderDir;
    }

    public DeviceSettingsManager getDeviceSettings() {
        return deviceSettings;
    }

    public void setDeviceSettings(DeviceSettingsManager deviceSettings) {
        this.deviceSettings = deviceSettings;
    }
    
    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ex) {
            dOPM_hostframeLogger.log(java.util.logging.Level.SEVERE, null, ex);
        }

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new dOPM_hostframe().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton browseDirectoryField;
    private javax.swing.ButtonGroup buttonGroup1;
    private javax.swing.JMenuItem clearLogsMenuItem;
    private javax.swing.JTextArea debugTextArea;
    private javax.swing.JPanel fileSettingsPanel;
    private javax.swing.JTextField fracOfMaxMirrorField;
    private javax.swing.JLabel fracOfMaxMirrorLabel;
    private javax.swing.JTextField fracOfMaxXyField;
    private javax.swing.JLabel fracOfMaxXyLabel;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JMenu jMenu2;
    private javax.swing.JMenuBar jMenuBar1;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JProgressBar jProgressBar;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JCheckBox mirrorMaxSpeedCheckBox;
    private javax.swing.JTextField mirrorScanIntervalField;
    private javax.swing.JTextField mirrorScanLengthField;
    private javax.swing.JRadioButton mirrorScanRadioButton;
    private javax.swing.JPanel mirrorScanSettingsPanel;
    private javax.swing.JTextField mirrorScanSpeedField;
    private javax.swing.JMenuItem openDeviceConfigMenuItem;
    private javax.swing.JTextField saveDirectoryField;
    private javax.swing.JLabel saveDirectoryLabel;
    private javax.swing.JCheckBox saveToDiskCheckBox;
    private javax.swing.JLabel scanIntervalLabel;
    private javax.swing.JLabel scanIntervalLabel1;
    private javax.swing.JLabel scanLengthLabel;
    private javax.swing.JLabel scanLengthLabel1;
    private javax.swing.JLabel scanSpeedLabel;
    private javax.swing.JLabel scanSpeedLabel1;
    private javax.swing.JLabel scanStageTypeLabel;
    private javax.swing.JButton snapTestButton;
    private javax.swing.JButton startButton;
    private javax.swing.JButton stopButton;
    private javax.swing.JComboBox<String> triggerModeComboBox;
    private javax.swing.JLabel triggerModeLabel;
    private javax.swing.JCheckBox view1CheckBox;
    private javax.swing.JCheckBox view2CheckBox;
    private javax.swing.JLabel viewsLabel;
    private javax.swing.JRadioButton xScanRadioButton;
    private javax.swing.JCheckBox xyMaxSpeedCheckBox;
    private javax.swing.JTextField xyScanIntervalField;
    private javax.swing.JTextField xyScanLengthField;
    private javax.swing.JTextField xyScanSpeedField;
    private javax.swing.JPanel xyStageScanSettingsPanel;
    private javax.swing.JRadioButton yScanRadioButton;
    // End of variables declaration//GEN-END:variables
}