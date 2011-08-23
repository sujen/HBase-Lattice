package com.inadco.hbl.model;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.annotation.PostConstruct;

import org.apache.commons.lang.Validate;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.inadco.hbl.api.Cube;
import com.inadco.hbl.api.Cuboid;
import com.inadco.hbl.compiler.Pig8CubeIncrementalCompilerBean;
import com.inadco.hbl.compiler.YamlModelParser;
import com.inadco.hbl.util.IOUtil;

@Component("HblAdmin")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class HblAdmin {
    
    private static final Logger s_log = Logger.getLogger(HblAdmin.class);

    public static final String HBL_METRIC_FAMILY_STR = "hbl";
    public static final byte[] HBL_METRIC_FAMILY     = Bytes.toBytes(HBL_METRIC_FAMILY_STR);

    private Resource           cubeModel;
    private String             cubeModelYamlStr;
    private Cube               cube;

    /**
     * Non-spring constructor
     * 
     * @param cubeModel
     */
    public HblAdmin(Resource cubeModel) throws IOException {
        this.cubeModel = cubeModel;
        init();
    }

    public Resource getCubeModel() {
        return cubeModel;
    }

    @Required
    public void setCubeModel(Resource cubeModel) {
        this.cubeModel = cubeModel;
    }

    @PostConstruct
    public void init() throws IOException {
        Validate.notNull(cubeModel);
        Deque<Closeable> closeables = new ArrayDeque<Closeable>();
        try {
            InputStream cubeIs = cubeModel.getInputStream();
            closeables.addFirst(cubeIs);
            cubeModelYamlStr = Pig8CubeIncrementalCompilerBean.fromStream(cubeIs, "utf-8");
            cube = YamlModelParser.parseYamlModel(cubeModelYamlStr);

        } finally {
            IOUtil.closeAll(closeables);
        }
    }

    /**
     * deploys the cube model to hbase by creating necessary tables. if table
     * already exists, it doesn't currently change its attributes.
     * 
     */
    public void deployCube(Configuration conf) throws IOException {
        HBaseAdmin hba = new HBaseAdmin(conf);
        for (Cuboid c : cube.getCuboids())
            // TODO: parse those things from cuboid description
            initCuboid(hba, c);
    }

    private void initCuboid(HBaseAdmin admin, Cuboid c) throws IOException {
        
        byte[] tablename = Bytes.toBytes(c.getCuboidTableName());
        
        if ( admin.tableExists(tablename ) ) { 
            s_log.info ( String.format ( "cuboid table %s already exists, cowardly refusing to re-create...",c.getCuboidTableName()));
            return;
        }
        
        HTableDescriptor htd = new HTableDescriptor(tablename);

        HColumnDescriptor hcd = new HColumnDescriptor(HBL_METRIC_FAMILY);
        hcd.setMaxVersions(c.getHbaseMaxVersions());
        hcd.setInMemory(c.isHbaseInMemory());
        hcd.setTimeToLive(c.getHbaseTTL());

        htd.addFamily(hcd);

        admin.createTable(htd);

    }
}