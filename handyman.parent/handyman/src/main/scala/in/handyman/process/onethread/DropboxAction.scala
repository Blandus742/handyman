package in.handyman.process.onethread

import com.typesafe.scalalogging.LazyLogging
import in.handyman.command.Context
import scala.collection.JavaConversions._
import in.handyman.command.Action
import in.handyman.command.CommandProxy
import in.handyman.util.ResourceAccess
import org.eclipse.emf.common.util.EList
import in.handyman.util.ParameterisationEngine
import java.util.ArrayList
import java.sql.SQLException
import scala.util.control.Exception.Finally
import java.io.{ BufferedReader, FileOutputStream, InputStreamReader }
import com.dropbox.core.{ DbxAppInfo, DbxRequestConfig, DbxWebAuth }
import com.dropbox.core.v2.DbxClientV2

import scala.util.Try
import com.dropbox.core.v2.files.Metadata
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import org.slf4j.MarkerFactory
import scala.collection.JavaConversions._
import scala.Array
import scala.util.control._
import java.io.File
import java.util.zip._




class DropboxAction extends in.handyman.command.Action with LazyLogging {
  val detailMap = new java.util.HashMap[String, String]
  val auditMarker = "DROPBOX";
  val aMarker = MarkerFactory.getMarker(auditMarker);

  def execute(context: Context, action: in.handyman.dsl.Action, actionId: Integer): Context = {
    val dropboxAsIs: in.handyman.dsl.Dropbox = action.asInstanceOf[in.handyman.dsl.Dropbox]
    val dropbox: in.handyman.dsl.Dropbox = CommandProxy.createProxy(dropboxAsIs, classOf[in.handyman.dsl.Dropbox], context)

    val source = dropbox.getSource
    var target = dropbox.getTarget
    val name = dropbox.getName
    val ddlSql: String = dropbox.getValue.replaceAll("\"", "")
    val id = context.getValue("process-id")
    val db = dropbox.getDb
    val auth = dropbox.getAuth
    val filetype = dropbox.getType
    val limit = dropbox.getLimit

    val dropboxDbConnfrom = ResourceAccess.rdbmsConn(db)
    val dropboxStmtfrom = dropboxDbConnfrom.createStatement
    dropboxDbConnfrom.setAutoCommit(false)
    val now = "now()"    
    var excep = false
    var procName =if(name.contains("Daily")) "Daily Files Process" else "Hourly Folder Process"
    var currFileName=""
    
      
    var ftype: Metadata = null
    var outputStream: FileOutputStream = null
    
    /*2020-04-17 17:11:20*/
    
    if (target.contains("\\")) {
      target = target.replace("\\", "\\\\")
    }
    var filesDownloaded = 0;
    var isHourlyFolderFiles = false
    
    var types = filetype.split(" ")
    var sequence = types(0)
    var fileorFolder = types(1)
    
    try {
      
      var pName = if(sequence.equalsIgnoreCase("hourly")) "Hourly Folder Process" else "Daily Files Process"
      var insertQuery= "insert into dropbox" + " (Process_id,Name,ProcessStarttime,DownloadStatus,UploadStatus,DataLoadingStatus,ProcessStatus,Rerun_Count) values " + "(" + id +  ",\""+pName +"\"," + now + "," + "\"Started\","+"\"Not Started\"," +"\"Not Started\"," +"\"Dropbox Download Started\",0"+" );"
      dropboxStmtfrom.execute(insertQuery)
      dropboxDbConnfrom.commit()
      
      var lastDownloadTime = ""
      val rs = dropboxStmtfrom.executeQuery(ddlSql)
      while (rs.next()) 
      {
        lastDownloadTime = rs.getString("lastDownloadTime")
      }
      println("Last Download Time: "+lastDownloadTime)
      lastDownloadTime = lastDownloadTime.stripSuffix(".0")
      
      val format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
          
      //lastDownloadTime = "2020-07-19 06:06:22"
      logger.info("Formatted Last Download Time: "+format.parse(lastDownloadTime))

      // azavea/rf-dropbox-test
      val config = new DbxRequestConfig("jcdropboxtest")
      val client = new DbxClientV2(config, auth)

      val items = client.files.listFolder("").getEntries
      var countToCloseOutputStream = 0
      
      val outer = new Breaks;
      val inner = new Breaks;

      if (source == null || source == "") 
      {
          outer.breakable 
          {
              for (item <- items)
              {
                  var fileName = "/" + item.getName();
                  ftype = client.files().getMetadata(fileName)
                  if(fileName.contains("Hourly") && sequence.equalsIgnoreCase("hourly") && fileorFolder.equalsIgnoreCase("files"))
                  {
                        if (ftype.isInstanceOf[FolderMetadata])
                        {
                            val items = client.files.listFolder(fileName).getEntries
                            inner.breakable
                            {
                                for (item <- items) 
                                {              
                                    var fname = "/" + item.getName();
                                    var filetype = client.files().getMetadata(fileName+fname)
                                    
                                    val itemMetadata =item.toString().split(",")
                                    var chksum :Array[String]=null
                                    var size :Array[String]=null
                                    var clientModifiedTime :Array[String]=null
                                    var serverModifiedTime :Array[String]=null
                                    for (i <- 0.until(itemMetadata.length)) 
                                    {
                                        //println(itemMetadata(i))
                                        if(itemMetadata(i).contains("content_hash"))
                                        {
                                          chksum= itemMetadata(i).split(":")
                                        }
                                        if(itemMetadata(i).contains("size"))
                                        {
                                          size= itemMetadata(i).split(":")
                                        }
                                        if(itemMetadata(i).contains("client_modified"))
                                        {
                                          clientModifiedTime= itemMetadata(i).split(":",2)
                                        }
                                        if(itemMetadata(i).contains("server_modified"))
                                        {
                                          serverModifiedTime= itemMetadata(i).split(":",2)
                                        }
                                    }
                                    var fileChecksum =  chksum(1).replaceAll("\\W", "")
                                    var fileSize = size(1).replaceAll("\\W", "") + " KB"
                                    var cmtime = clientModifiedTime(1).replace("\"", "").replace(",", "").replace("T"," ").replace("Z", "")
                                    var smtime = serverModifiedTime(1).replace("\"", "").replace(",", "").replace("T"," ").replace("Z", "")
                                    
                                   
                                    if(format.parse(cmtime).compareTo(format.parse(lastDownloadTime))>0 || format.parse(smtime).compareTo(format.parse(lastDownloadTime))>0 )
                                    {
                                        //println("CM Date after last download")
                                        isHourlyFolderFiles = true
                                        downloadFilesFromDropbox(fileName+fname,filetype, target,fileChecksum,fileSize)
                                        filesDownloaded  +=1
                                        logger.info("File downloaded: "+fileName.stripPrefix("/")+fname)
                                        logger.info("Files Downloaded--------------->"+filesDownloaded)
                                        if (countToCloseOutputStream == 15) 
                                        {
                                            outputStream.close()
                                            countToCloseOutputStream = 0;
                                            logger.info("Closing Output Stream!")
                                        }
                                        countToCloseOutputStream += 1
                                    }
                                  if(filesDownloaded == limit.toInt)
                                  {
                                      logger.info("-------------->Breaking inner loop while traversing folder contents<--------------")
                                      inner.break                  
                                  }
                                }
                            }
                            if(filesDownloaded == limit.toInt)   
                            {
                                logger.info("-------------->Breaking outer loop while traversing folder contents<--------------")
                                outer.break                   
                            }
                            
                          }
                  }
                  else if(fileName.contains("Hourly") && sequence.equalsIgnoreCase("hourly") && fileorFolder.equalsIgnoreCase("folder"))
                  {
                        downloadFilesFromDropbox(fileName, ftype,target,"-","-") 
                        filesDownloaded  +=1
                        logger.info("File downloaded: "+fileName)
                        logger.info("Files Downloaded--------------->"+filesDownloaded)
                        if (countToCloseOutputStream == 15) 
                        {
                          outputStream.close()
                          countToCloseOutputStream = 0;
                          logger.info("Closing Output Stream!")
                        }
                        countToCloseOutputStream += 1
                        if(filesDownloaded == limit.toInt)   
                        {
                            logger.info("-------------->Breaking outer loop after downloading Hourly folder as a zip<--------------")
                            outer.break                   
                        }  
                  }
                  else if(!fileName.contains("Hourly") && sequence.equalsIgnoreCase("daily") && ftype.isInstanceOf[FileMetadata])
                  {
                      isHourlyFolderFiles = false
                      val itemMetadata =item.toString().split(",")
                      var chksum :Array[String]=null
                      var size :Array[String]=null
                      var clientModifiedTime :Array[String]=null
                      var serverModifiedTime :Array[String]=null
                      for (i <- 0.until(itemMetadata.length)) 
                      {
                        //println(itemMetadata(i))
                          if(itemMetadata(i).contains("content_hash"))
                          {
                            chksum= itemMetadata(i).split(":")
                          }
                          if(itemMetadata(i).contains("size"))
                          {
                            size= itemMetadata(i).split(":")
                          }
                          if(itemMetadata(i).contains("client_modified"))
                          {
                            clientModifiedTime= itemMetadata(i).split(":",2)
                          }
                          if(itemMetadata(i).contains("server_modified"))
                          {
                            serverModifiedTime= itemMetadata(i).split(":",2)
                          }
                      }
                      var fileChecksum =  chksum(1).replaceAll("\\W", "")
                      var fileSize = size(1).replaceAll("\\W", "") + " KB"
                      var cmtime = clientModifiedTime(1).replace("\"", "").replace(",", "").replace("T"," ").replace("Z", "")
                      var smtime = serverModifiedTime(1).replace("\"", "").replace(",", "").replace("T"," ").replace("Z", "")
                      
                      if(format.parse(cmtime).compareTo(format.parse(lastDownloadTime))>0 || format.parse(smtime).compareTo(format.parse(lastDownloadTime))>0 )
                      {
                          downloadFilesFromDropbox(fileName, ftype,target,fileChecksum,fileSize) 
                          filesDownloaded  +=1
                          logger.info("File downloaded: "+fileName)
                          logger.info("Files Downloaded--------------->"+filesDownloaded)
                          if (countToCloseOutputStream == 15) 
                          {
                            outputStream.close()
                            countToCloseOutputStream = 0;
                            logger.info("Closing Output Stream!")
                          }
                          countToCloseOutputStream += 1
                      }
                      if(filesDownloaded == limit.toInt)   
                      {
                          logger.info("------------->Breaking outer loop while traversing file contents<--------------")
                          outer.break                   
                      }                   
                      
                  }
                  else if(fileName.contains("Daily") && sequence.equalsIgnoreCase("daily") && fileorFolder.equalsIgnoreCase("folder"))
                  {
                        downloadFilesFromDropbox(fileName, ftype,target,"-","-") 
                        filesDownloaded  +=1
                        logger.info("File downloaded: "+fileName)
                        logger.info("Files Downloaded--------------->"+filesDownloaded)
                        if (countToCloseOutputStream == 15) 
                        {
                          outputStream.close()
                          countToCloseOutputStream = 0;
                          logger.info("Closing Output Stream!")
                        }
                        countToCloseOutputStream += 1
                        if(filesDownloaded == limit.toInt)   
                        {
                            logger.info("-------------->Breaking outer loop after downloading Daily folder as a zip<--------------")
                            outer.break                   
                        }  
                  }
                    
                             
              }
          
          }
        
      } 
      else 
      {
        ftype = client.files().getMetadata(source)
        downloadFilesFromDropbox(source, ftype,target,"","")
      }

      
      def downloadFilesFromDropbox(source: String, ftype: Metadata,target : String, fileChecksum:String, fileSize:String) {
        var procName =""
        if (ftype.isInstanceOf[FileMetadata]) 
        {
            var targetdirec = ""
            var sourcedirec=""
            if(isHourlyFolderFiles == true) 
            {
              if(target.contains("\\")){
                targetdirec = target + "\\\\MCA Suite Hourly Data"
                sourcedirec = source.replaceAll("/MCASuiteHourlyBackup/", "\\\\")
              }
              else{
                targetdirec = target 
                sourcedirec ="/" + source.replaceAll("/MCASuiteHourlyBackup/", "")
              }
              
            }
            else
            {
              if(target.contains("\\")){
                targetdirec = target + "\\\\MCA Suite Daily Data"
              }
              else{
                targetdirec = target
              }
              sourcedirec = source
            }
            
            outputStream = new FileOutputStream(targetdirec + sourcedirec)
            val downloadFile = Try {
              client.files
                .download(source)
                .download(outputStream)
              logger.info("Dowload from Dropbox id#{}, name#{}, from#{}, to#{}", id, name, source, targetdirec)
              logger.info("Download completed into this location#{}", targetdirec)
              var filePath = ""
              if(target.contains("\\")){
                filePath = targetdirec +"\\"+ sourcedirec.replace("/", "\\\\")
              }
              else{
                filePath = targetdirec +sourcedirec
              }
              procName = if (isHourlyFolderFiles == true) "Hourly Files Process" else "Daily Files Process"
              currFileName = source.stripPrefix("/")
              var auditQuery= "update dropbox " + "set Name =\""+procName+"\", Source=\""+ currFileName + "\", Target=\""+ targetdirec + "\", Filepath=\"" + filePath + "\", Checksum=\"" + fileChecksum + "\", Filesize=\"" + fileSize + "\", DownloadStatus=" +"\"Completed\", DropboxDownloadtime=" + now + ", ProcessStatus=\"Dropbox Download Complete\" where Process_id =" +id +";"
              var query = "insert into " + id + "_dropbox" + " (Process_id,Name,Source,Target,ProcessStarttime,Filepath,Checksum,Filesize,DataLoadingStatus,DownloadStatus,DropboxDownloadtime,ProcessStatus) values " + "(\"" + id + "\",\"" + procName + "\",\"" + currFileName + "\",\"" + targetdirec + "\"," + now + ",\"" + filePath + "\",\"" + fileChecksum + "\",\""+ fileSize + "\",\"Not Started\","+"\"Completed\"," + now+",\"Dropbox Download Complete\""+" );"      
              logger.info("Insert Query: "+query)
              logger.info("Insert Query in the Audit Trails table: "+auditQuery)
              try{
                dropboxStmtfrom.execute(query)
                dropboxStmtfrom.execute(auditQuery)
                dropboxDbConnfrom.commit()
              }
              catch{
                case ex: SQLException =>{
                  excep = true
                  ex.printStackTrace()
                }
              }
              
              logger.info("Inserted the data into db ")
            }

        }
        else if (ftype.isInstanceOf[FolderMetadata])
        {
            outputStream = new FileOutputStream(target + source+".zip")
            val downloadZip = Try {
              client.files
                .downloadZip(source)
                .download(outputStream)
              logger.info("Dowload from Dropbox id#{}, name#{}, from#{}, to#{}", id, name, source, target)
              logger.info("Download completed into this location#{}", target)
              
              
              var filePath = ""
              if(target.contains("\\")){
                filePath = target + source.replace("/", "\\\\") + ".zip"
              }
              else{
                filePath = target +source + ".zip"
              }
              val fileSize = new File(filePath).length().toString()  +" KB"
              procName = "Hourly Folder Process"
              currFileName = source.stripPrefix("/")+".zip"
              var auditQuery= "update dropbox " + "set Name =\""+procName+"\", Source=\""+ currFileName + "\", Target=\""+ target + "\", Filepath=\"" + filePath + "\", Filesize=\"" + fileSize + "\", DownloadStatus=" +"\"Completed\", DropboxDownloadtime=" + now + ", ProcessStatus=\"Dropbox Download Complete\" where Process_id =" +id +";"
              //var auditQuery = "insert into dropbox" + " (Process_id,Name,Source,Target,ProcessStarttime,Filepath,DownloadStatus,DropboxDownloadtime,ProcessStatus,FileSize) values " + "(\"" + id + "\",\"" + procName + "\",\"" + source + "\",\"" + target + "\"," + now + ",\"" + filePath +  "\","+"\"Completed\"," + now+",\"Dropbox Download Complete\""+",\""+fileSize +"\" );"
              var query = "insert into " + id + "_dropbox" + " (Process_id,Name,Source,Target,ProcessStarttime,Filepath,DownloadStatus,DropboxDownloadtime,ProcessStatus,FileSize) values " + "(\"" + id + "\",\"" + procName + "\",\"" + currFileName + "\",\"" + target + "\"," + now + ",\"" + filePath +  "\","+"\"Completed\"," + now+",\"Dropbox Download Complete\""+",\""+fileSize +"\" );"  
              logger.info("Insert Query: "+query)
              logger.info("Insert Query in the Audit Trails table: "+auditQuery)
              try{
                dropboxStmtfrom.execute(query)
                dropboxStmtfrom.execute(auditQuery)
                dropboxDbConnfrom.commit()
              }
              catch{
                case ex: SQLException =>{
                  excep = true
                  ex.printStackTrace()
                }
              }
              logger.info("Inserted the data into db ")
            }
        }
          /**/
        
      }
      
      
      
    } 
    catch 
    {
      
      case ex: SQLException => {
        excep = true
        ex.printStackTrace()
      }
      
      case ex : com.dropbox.core.NetworkIOException => {
        excep = true
        ex.printStackTrace()
      }
      case ex : Exception => {
        excep = true
        ex.printStackTrace()
      }
    } 
    finally 
    {
      if(filesDownloaded >0)
      {
          outputStream.close()
      } 
      if(filesDownloaded ==0)
      {
            logger.info("No files downloaded in the process.........All files upto Date")
            var auditQuery = "update dropbox set Name='" +  procName + "' , ProcessStatus = 'Complete' where Process_id ="+id+" and ProcessStatus='Dropbox Download Started';"
            var query = "insert into " + id + "_dropbox" + " (Process_id,Name,ProcessStarttime,ProcessStatus) values " + "(\"" + id + "\",\"" + procName + "\","  + now + ",\"" +"Complete\""+" );"  
            logger.info("Insert Query: "+query)
            logger.info("Insert Query in the Audit Trails table: "+auditQuery)
            dropboxStmtfrom.execute(query)
            dropboxStmtfrom.execute(auditQuery)
            dropboxDbConnfrom.commit()
            logger.info("Inserted the data into db ")
      } 
      if(excep == true)
      {
          var auditQuery= "update dropbox " + "set DownloadStatus=\"Failed\", ProcessStatus=\"Dropbox Download Failed\" where Process_id =" +id +" and Source =\""+currFileName+"\";"
          var query = "update "+id+"_dropbox " + "set ProcessStatus=\"Failed\" where Process_id =" +id +" and Source =\""+currFileName+"\";"      
          logger.info("Update Query in case of exception: "+query)
          logger.info("Update Query in case of exception in the Audit Trails table: "+auditQuery)
          dropboxStmtfrom.execute(query)
          dropboxStmtfrom.execute(auditQuery)
          dropboxDbConnfrom.commit()
          logger.info("Updated the status of failed process in the DB. ")
      }
      
      detailMap.put("name", name)
      detailMap.put("source", source)
      detailMap.put("target", target)
      detailMap.put("ddlSql", ddlSql)
      detailMap.put("Files downloaded",filesDownloaded.toString())
    }
    context

  }

  def executeIf(context: Context, action: in.handyman.dsl.Action): Boolean = {
    val dropboxAsIs: in.handyman.dsl.Dropbox = action.asInstanceOf[in.handyman.dsl.Dropbox]
    val dropbox: in.handyman.dsl.Dropbox = CommandProxy.createProxy(dropboxAsIs, classOf[in.handyman.dsl.Dropbox], context)

    val expression = dropbox.getCondition
    try {
      val output = ParameterisationEngine.doYieldtoTrue(expression)
      detailMap.putIfAbsent("condition-output", output.toString())
      output
    } finally {
      if (expression != null)
        detailMap.putIfAbsent("condition", "LHS=" + expression.getLhs + ", Operator=" + expression.getOperator + ", RHS=" + expression.getRhs)

    }

  }

  def generateAudit(): java.util.Map[String, String] = {
    detailMap
  }
  //target : String , source : String , auth : String , id :String, name: String, dropboxStmtfrom : Statement

}