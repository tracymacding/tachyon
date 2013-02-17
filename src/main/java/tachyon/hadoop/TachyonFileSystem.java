package tachyon.hadoop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tachyon.CommonUtils;
import tachyon.Config;
import tachyon.client.TachyonClient;
import tachyon.thrift.NetAddress;

/**
 * This is only for compatibility with Hadoop stack.
 * 
 * @author haoyuan
 */
public class TachyonFileSystem extends FileSystem {
  private final Logger LOG = LoggerFactory.getLogger(TachyonFileSystem.class);

  private URI mUri = null;
  private Path mWorkingDir = new Path("/");
  private TachyonClient mTachyonClient = null;
  private String mTachyonHeader = null;

  @Override
  public FSDataOutputStream append(Path path, int bufferSize, Progressable progress)
      throws IOException {
    LOG.info("TachyonFileSystem append(" + path + ", " + bufferSize + ", " + progress + ")");
    throw new IOException("Not supported");
  }

  @Override
  public FSDataOutputStream create(Path cPath, FsPermission permission, boolean overwrite,
      int bufferSize, short replication, long blockSize, Progressable progress)
          throws IOException {
    LOG.info("TachyonFileSystem create(" + cPath + ", " + permission + ", " + overwrite + 
        ", " + bufferSize + ", " + replication + ", " + blockSize + ", " + progress + ")");

    String path = Utils.getPathWithoutScheme(cPath);
    if (path.contains("%")) {
      CommonUtils.runtimeException("Save into Tachyon could not be with a DATASET ID");
    }

    Path hdfsPath = Utils.getHDFSPath(path);
    FileSystem fs = hdfsPath.getFileSystem(getConf());
    LOG.info("TachyonFileSystem mkdirs: making dir " + hdfsPath);

    if (!path.contains(Config.HDFS_TEMP_FILE)) {
      throw new IOException("Not supported");
    }

    return fs.create(hdfsPath, permission, overwrite, bufferSize, replication, blockSize,
        progress);
  }

  @Override
  @Deprecated
  public boolean delete(Path path) throws IOException {
    throw new IOException("Not supported");
  }

  @Override
  public boolean delete(Path path, boolean recursive) throws IOException {
    LOG.info("TachyonFileSystem delete(" + path + ", " + recursive + ")");
    Path hdfsPath = Utils.getHDFSPath(path);
    FileSystem fs = hdfsPath.getFileSystem(getConf());
    LOG.info("TachyonFileSystem delete(" + hdfsPath + ", " + recursive + ")");
    // TODO Need to add Tachyon part.
    return fs.delete(hdfsPath, recursive);
  }

  @Override
  /**
   * Return the status of a single file.
   * 
   * If the file does not exist in Tachyon, query it from HDFS. 
   */
  public FileStatus getFileStatus(Path path) throws IOException {
    String filePath = Utils.getPathWithoutScheme(path);
    Path hdfsPath = Utils.getHDFSPath(filePath);

    LOG.info("TachyonFileSystem getFilesStatus(" + path + "): Corresponding HDFS Path: " + hdfsPath);

    FileSystem fs = hdfsPath.getFileSystem(getConf());
    FileStatus hfs = fs.getFileStatus(hdfsPath);

    String tFileSuffix = "";
    if (!hfs.isDir() && !filePath.contains(Config.HDFS_TEMP_FILE)) {
      int fileId = mTachyonClient.getFileId(filePath);
      if (fileId > 0) {
        LOG.info("TachyonClient has file " + filePath);
        tFileSuffix = "%" + fileId;
      } else {
        LOG.info("TachyonClient does not have DATASET " + filePath);
        int tmp = mTachyonClient.createFile(filePath);
        // TODO Add Checkpoint Path.
        if (tmp != -1) {
          tFileSuffix = "%" + tmp;
        }
      }
    }

    FileStatus ret = new FileStatus(hfs.getLen(), hfs.isDir(), hfs.getReplication(),
        Integer.MAX_VALUE, hfs.getModificationTime(), hfs.getAccessTime(), hfs.getPermission(),
        hfs.getOwner(), hfs.getGroup(), new Path(mTachyonHeader + filePath + tFileSuffix));

    LOG.debug("HFS: " + Utils.toStringHadoopFileStatus(hfs));
    LOG.debug("TFS: " + Utils.toStringHadoopFileStatus(ret));

    return ret;
  }

  @Override
  public URI getUri() {
    LOG.info("TachyonFileSystem getUri() with return " + mUri);
    return mUri;
  }

  @Override
  public Path getWorkingDirectory() {
    LOG.info("TachyonFileSystem getWorkingDirectory() with return " + mWorkingDir);
    return mWorkingDir;
  }

  @Override
  public BlockLocation[] getFileBlockLocations(FileStatus file, long start, long len) 
      throws IOException {
    if (file == null) {
      return null;
    }
    String path = Utils.getPathWithoutScheme(file.getPath());
    BlockLocation ret = null;

    String rawPath = path;
    int fileId = -1;
    ArrayList<String> names = new ArrayList<String>();
    ArrayList<String> hosts = new ArrayList<String>();

    if (path.contains("%")) {
      String[] list = path.split("%");
      rawPath = list[0];
      fileId = Integer.parseInt(list[1]);

      List<NetAddress> locations = mTachyonClient.getFileLocations(fileId);
      if (locations != null) {
        for (int k = 0; k < locations.size(); k ++) {
          names.add(locations.get(k).mHost);
          hosts.add(locations.get(k).mHost);
        }

        if (hosts.size() > 0) {
          ret = new BlockLocation(CommonUtils.toStringArray(names),
              CommonUtils.toStringArray(hosts), 0, Long.MAX_VALUE);
        }
      }
    }

    if (ret == null) {
      Path hdfsPath = Utils.getHDFSPath(rawPath);
      FileSystem fs = hdfsPath.getFileSystem(getConf());
      FileStatus hdfsFileStatus = new FileStatus(file.getLen(), file.isDir(),
          file.getReplication(), file.getBlockSize(), file.getModificationTime(), 
          file.getAccessTime(), file.getPermission(), file.getOwner(), file.getGroup(),
          hdfsPath);
      return fs.getFileBlockLocations(hdfsFileStatus, 0, 1);
    }

    BlockLocation[] res = new BlockLocation[1];
    res[0] = ret;
    return res;
  }

  @Override
  /**
   * Initialize the class, have a lazy connection with Tachyon through mTC.
   */
  public void initialize(URI uri, Configuration conf) throws IOException {
    LOG.info("TachyonFileSystem initialize(" + uri + ", " + conf + "). Connecting TachyonSystem: " +
        uri.getHost() + ":" + uri.getPort());
    mTachyonClient = TachyonClient.getClient(
        new InetSocketAddress(uri.getHost(), uri.getPort()));
    mTachyonHeader = "tachyon://" + uri.getHost() + ":" + uri.getPort() + "/";
  }

  @Override
  /**
   * Return all files in the path.
   */
  public FileStatus[] listStatus(Path path) throws IOException {
    String filePath = Utils.getPathWithoutScheme(path);
    Path hdfsPath = Utils.getHDFSPath(filePath);
    LOG.info("TachyonFileSystem listStatus(" + path + "): Corresponding HDFS Path: " + hdfsPath);
    FileSystem fs = hdfsPath.getFileSystem(getConf());
    FileStatus[] hfs = fs.listStatus(hdfsPath);
    for (FileStatus thfs : hfs) {
      if (thfs.isDir()) {
        CommonUtils.illegalArgumentException(new IOException("Not a file: " +
            mTachyonHeader + Utils.getPathWithoutScheme(thfs.getPath())));
      }
    }
    ArrayList<FileStatus> tRet = new ArrayList<FileStatus>();
    for (int k = 0; k < hfs.length; k ++) {
      tRet.add(getFileStatus(hfs[k].getPath()));
    }
    FileStatus[] ret = new FileStatus[hfs.length];
    ret = tRet.toArray(ret);

    return ret;
  }
  //  public FileStatus[] listStatus(Path path) throws IOException {
  //    String datasetPath = Utils.getPathWithoutScheme(path);
  //    Path hdfsPath = Utils.getHDFSPath(datasetPath);
  //    LOG.info("TachyonFileSystem listStatus(" + path + "): Corresponding HDFS Path: " + hdfsPath);
  //    FileSystem fs = hdfsPath.getFileSystem(getConf());
  //    FileStatus[] hfs = fs.listStatus(hdfsPath);
  //    for (FileStatus thfs : hfs) {
  //      if (thfs.isDir()) {
  //        CommonUtils.illegalArgumentException(new IOException("Not a file: " +
  //            mTachyonHeader + Utils.getPathWithoutScheme(thfs.getPath())));
  //      }
  //    }
  //
  //    String DATASET_NAME = "";
  //    int datasetId = mTachyonClient.getDatasetId(datasetPath); 
  //    if (datasetId > 0) {
  //      LOG.info("TachyonClient has DATASET " + datasetPath);
  //      Dataset dataset = mTachyonClient.getDataset(datasetPath);
  //      if (dataset != null) {
  //        DATASET_NAME = "%" + dataset.getDatasetId() + "%";
  //      }
  //    }
  //    if (datasetId == 0) {
  //      LOG.info("TachyonClient does not have DATASET " + datasetPath);
  //      int tmp = mTachyonClient.createDataset(datasetPath, hfs.length);
  //      // TODO Add Checkpoint Path.
  //      if (tmp != -1) {
  //        DATASET_NAME = "%" + tmp + "%";
  //      }
  //    }
  //    ArrayList<FileStatus> tRet = new ArrayList<FileStatus>();
  //    // TODO For now, we reply on that the fs.liststatus() return files in the same order.
  //    for (int k = 0; k < hfs.length; k ++) {
  //      FileStatus tfs = hfs[k];
  //      LOG.debug("HFS: " + Utils.toStringHadoopFileStatus(tfs));
  //      String t = "";
  //      if (!DATASET_NAME.equals("")) {
  //        t = DATASET_NAME + k;
  //      }
  //      FileStatus cfs = new FileStatus(tfs.getLen(), tfs.isDir(), tfs.getReplication(),
  //          Integer.MAX_VALUE, tfs.getModificationTime(), tfs.getAccessTime(),
  //          tfs.getPermission(), tfs.getOwner(), tfs.getGroup(),
  //          new Path(mTachyonHeader + Utils.getPathWithoutScheme(tfs.getPath()) + t));
  //      tRet.add(cfs);
  //      LOG.debug("CFS: " + Utils.toStringHadoopFileStatus(cfs));
  //    }
  //
  //    FileStatus[] ret = new FileStatus[tRet.size()];
  //    ret = tRet.toArray(ret);
  //
  //    return ret;
  //  }


  @Override
  public boolean mkdirs(Path cPath, FsPermission permission) throws IOException {
    LOG.info("TachyonFileSystem mkdirs(" + cPath + ", " + permission + ")");

    String path = Utils.getPathWithoutScheme(cPath);
    if (path.contains("%")) {
      CommonUtils.runtimeException("Save into Tachyon could not be with a DATASET ID");
    }

    Path hdfsPath = Utils.getHDFSPath(path);
    FileSystem fs = hdfsPath.getFileSystem(getConf());
    LOG.info("TachyonFileSystem mkdirs: making dir " + hdfsPath);
    return fs.mkdirs(hdfsPath);
  }

  @Override
  /**
   * Return the inputstream of a partition.
   */
  public FSDataInputStream open(Path cPath, int bufferSize) throws IOException {
    LOG.info("TachyonFileSystem open(" + cPath + ", " + bufferSize + ")");

    String path = Utils.getPathWithoutScheme(cPath);

    String rawPath = path;
    int fileId = -1;

    if (path.contains("%")) {
      String[] list = path.split("%");
      rawPath = list[0];
      fileId = Integer.parseInt(list[1]);
    } else {
      Path hdfsPath = Utils.getHDFSPath(rawPath);
      FileSystem fs = hdfsPath.getFileSystem(getConf());
      return fs.open(hdfsPath, bufferSize);
    }

    return new FSDataInputStream(new TFileInputStreamHdfs(mTachyonClient, fileId,
        Utils.getHDFSPath(rawPath), getConf(), bufferSize));
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    LOG.info("TachyonFileSystem rename(" + src + ", " + dst + ")");
    Path hSrc = Utils.getHDFSPath(src);
    Path hDst = Utils.getHDFSPath(dst);
    FileSystem fs = hSrc.getFileSystem(getConf());
    // TODO Need to add the Tachyon part.
    return fs.rename(hSrc, hDst); 
  }

  @Override
  public void setWorkingDirectory(Path path) {
    LOG.info("TachyonFileSystem setWorkingDirectory(" + path + ")");
    if (path.isAbsolute()) {
      mWorkingDir = path;
    } else {
      mWorkingDir = new Path(mWorkingDir, path);
    }
  }
}