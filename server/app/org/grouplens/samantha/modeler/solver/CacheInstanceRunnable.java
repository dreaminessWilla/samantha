package org.grouplens.samantha.modeler.solver;

import org.grouplens.samantha.modeler.common.LearningData;
import org.grouplens.samantha.modeler.common.LearningInstance;
import org.grouplens.samantha.server.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;

public class CacheInstanceRunnable implements ObjectiveRunnable {
    final private static Logger logger = LoggerFactory.getLogger(CacheInstanceRunnable.class);
    private final String cachePath;
    private final LearningData data;
    private long cnt = 0;

    public CacheInstanceRunnable(String cachePath, LearningData data) {
        this.cachePath = cachePath;
        this.data = data;
    }

    @Override
    public void run() {
        try {
            ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(cachePath));
            List<LearningInstance> instances;
            while ((instances = data.getLearningInstance()).size() > 0) {
                for (LearningInstance ins : instances) {
                    cnt++;
                    outputStream.writeUnshared(ins);
                    outputStream.reset();
                    if (cnt % 1000000 == 0) {
                        logger.info("Cached {} instances.", cnt);
                    }
                }
            }
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            logger.error(e.getMessage());
            throw new BadRequestException(e);
        }
    }

    public double getObjVal() {
        return cnt;
    }
}