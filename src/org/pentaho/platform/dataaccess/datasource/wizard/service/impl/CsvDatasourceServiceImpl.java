/*
 * This program is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software 
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this 
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html 
 * or from the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright 2009-2010 Pentaho Corporation.  All rights reserved.
 *
 * Created Sep, 2010
 * @author jdixon
 */
package org.pentaho.platform.dataaccess.datasource.wizard.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Document;
import org.dom4j.tree.DefaultElement;
import org.pentaho.agilebi.modeler.ModelerException;
import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.gwt.GwtModelerWorkspaceHelper;
import org.pentaho.agilebi.modeler.services.impl.GwtModelerServiceImpl;
import org.pentaho.metadata.model.Domain;
import org.pentaho.platform.api.engine.IPentahoObjectFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IPentahoUrlFactory;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.dataaccess.datasource.DatasourceType;
import org.pentaho.platform.dataaccess.datasource.beans.BogoPojo;
import org.pentaho.platform.dataaccess.datasource.wizard.csv.CsvUtils;
import org.pentaho.platform.dataaccess.datasource.wizard.csv.FileUtils;
import org.pentaho.platform.dataaccess.datasource.wizard.models.CsvTransformGeneratorException;
import org.pentaho.platform.dataaccess.datasource.wizard.models.FileInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.sources.csv.FileTransformStats;
import org.pentaho.platform.dataaccess.datasource.wizard.models.ModelInfo;
import org.pentaho.platform.dataaccess.datasource.wizard.service.agile.AgileHelper;
import org.pentaho.platform.dataaccess.datasource.wizard.service.agile.CsvTransformGenerator;
import org.pentaho.platform.dataaccess.datasource.wizard.service.gwt.ICsvDatasourceService;
import org.pentaho.platform.engine.core.system.PentahoBase;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.plugin.action.kettle.KettleSystemListener;
import org.pentaho.platform.uifoundation.component.xml.PMDUIComponent;
import org.pentaho.platform.util.web.SimpleUrlFactory;
import org.pentaho.reporting.libraries.base.util.StringUtils;
import org.pentaho.ui.xul.XulServiceCallback;

@SuppressWarnings("unchecked")
public class CsvDatasourceServiceImpl extends PentahoBase implements ICsvDatasourceService {

  private static final long serialVersionUID = 2498165533158485182L;

  private Log logger = LogFactory.getLog(CsvDatasourceServiceImpl.class);

  private ModelerService modelerService = new ModelerService();

  private ModelerWorkspace modelerWorkspace;

  public CsvDatasourceServiceImpl(){
    super();
    modelerWorkspace = new ModelerWorkspace(new GwtModelerWorkspaceHelper());
    modelerService = new ModelerService();
  }

  public Log getLogger() {
    return logger;
  }
  
	public String getEncoding(String fileName) {
		String encoding = null;
		try {
			CsvUtils csvModelService = new CsvUtils();
			encoding = csvModelService.getEncoding(fileName);
		} catch (Exception e) {
			logger.error(e);
		}
		return encoding;
	}

  public ModelInfo stageFile(String fileName, String delimiter, String enclosure, boolean isFirstRowHeader, String encoding)
      throws Exception {
    ModelInfo modelInfo;
    try {
      CsvUtils csvModelService = new CsvUtils();
      int headerRows = isFirstRowHeader ? 1 : 0;
      modelInfo = csvModelService.generateFields("", fileName, AgileHelper.getCsvSampleRowSize(), delimiter, enclosure, headerRows, true, true, encoding); //$NON-NLS-1$
    } catch (Exception e) {
      logger.error(e);
      throw e;
    }
    return modelInfo;
  }

  public FileInfo[] getStagedFiles() throws Exception {
    FileInfo[] files;
    try {
      FileUtils fileService = new FileUtils();
      files = fileService.listFiles();
    } catch (Exception e) {
      logger.error(e);
      throw e;
    }
    return files;
  }

  public FileTransformStats generateDomain(ModelInfo modelInfo) throws Exception {
    IPentahoSession pentahoSession = null;
    try {
      pentahoSession = getSession();
      KettleSystemListener.environmentInit(pentahoSession);
      
      String statsKey = FileTransformStats.class.getSimpleName() + "_" + modelInfo.getFileInfo().getTmpFilename(); //$NON-NLS-1$

      FileTransformStats stats = new FileTransformStats();
      pentahoSession.setAttribute(statsKey, stats);
      CsvTransformGenerator csvTransformGenerator = new CsvTransformGenerator(modelInfo, AgileHelper.getDatabaseMeta());
      csvTransformGenerator.setTransformStats(stats);
      
      
      try {
        csvTransformGenerator.dropTable(modelInfo.getStageTableName());
      } catch (CsvTransformGeneratorException e) {
        // this is ok, the table may not have existed.
        logger.info("Could not drop table before staging"); //$NON-NLS-1$
      }
      csvTransformGenerator.createOrModifyTable(pentahoSession);

      // no longer need to truncate the table since we dropped it a few lines up, so just pass false
      csvTransformGenerator.loadTable(false, pentahoSession, true);

      ArrayList<String> combinedErrors = new ArrayList<String>(modelInfo.getCsvInputErrors());
      combinedErrors.addAll(modelInfo.getTableOutputErrors());
      stats.setErrors(combinedErrors);
      
      // wait until it it done
      while (!stats.isRowsFinished()) {
        Thread.sleep(200);
      }

      modelerWorkspace.setDomain(modelerService.generateCSVDomain(modelInfo.getStageTableName(), modelInfo.getDatasourceName()));

      modelerWorkspace.getWorkspaceHelper().autoModelFlat(modelerWorkspace);
      modelerWorkspace.setModelName(modelInfo.getDatasourceName());
      modelerWorkspace.getWorkspaceHelper().populateDomain(modelerWorkspace);
      Domain workspaceDomain = modelerWorkspace.getDomain();

      String serializedModel = modelerService.serializeModels(workspaceDomain, modelerWorkspace.getModelName());
      stats.setSerializedDomain(serializedModel);
      stats.setDomain(modelerWorkspace.getDomain());

      return stats;
    } catch (Exception e) {
      e.printStackTrace();
      logger.error(e);
      throw e;
    } finally {
      if (pentahoSession != null) {
        pentahoSession.destroy();
      }
    }
  }

  private IPentahoSession getSession() {
    IPentahoSession session = null;
    IPentahoObjectFactory pentahoObjectFactory = PentahoSystem.getObjectFactory();
    if (pentahoObjectFactory != null) {
      try {
        session = pentahoObjectFactory.get(IPentahoSession.class, "systemStartupSession", null); //$NON-NLS-1$
      } catch (ObjectFactoryException e) {
        logger.error(e);
      }
    } 
    return session;
  }

  public List<String> getPreviewRows(String filename, boolean isFirstRowHeader, int rows, String encoding) throws Exception {
    List<String> previewRows = null;
    if(!StringUtils.isEmpty(filename)) {
      CsvUtils service = new CsvUtils();
      ModelInfo mi = service.getFileContents("", filename, ",", "\"", rows, isFirstRowHeader, encoding); //$NON-NLS-1$  //$NON-NLS-2$  //$NON-NLS-3$  
      previewRows = mi.getFileInfo().getContents();
    }
    return previewRows;
  }

  @Override
  public BogoPojo gwtWorkaround(BogoPojo pojo) {
    return pojo;
  }

}
