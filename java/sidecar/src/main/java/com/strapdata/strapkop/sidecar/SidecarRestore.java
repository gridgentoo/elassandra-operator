package com.strapdata.strapkop.sidecar;


import com.instaclustr.backup.task.RestoreTask;
import com.instaclustr.backup.util.GlobalLock;
import com.strapdata.model.backup.RestoreArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@CommandLine.Command(name = "cassandra-restore",
        description = "Sidecar management application for Apache Cassandra running on Kubernetes."
)
public class SidecarRestore implements Callable<Void> {
    private static final Logger logger = LoggerFactory.getLogger(SidecarRestore.class);
    
    @CommandLine.Unmatched
    private String[] args = new String[0];
    
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec commandSpec;

    @SuppressWarnings( "deprecation" )
    public static void main(final String[] args) throws IOException {
        CommandLine.call(new SidecarRestore(), System.err, CommandLine.Help.Ansi.ON, args);
    }
    
    @Override
    public Void call() throws Exception {
        
        final RestoreArguments arguments = new RestoreArguments("cassandra-restore", System.err);
        arguments.parseArguments(args);
        
        Pattern p = Pattern.compile("(\\d+$)");
        Matcher m = p.matcher(String.join("", Files.readAllLines(Paths.get("/etc/podinfo/name"))));
        m.find();
        String ordinal = m.group();
        
        
        logger.info("detected ordinal is {}", ordinal);
        
        try {
            GlobalLock globalLock = new GlobalLock("/tmp");
            arguments.sourceNodeID = arguments.sourceNodeID  + "-"  + ordinal; //make getting the ordinal more robust
            new RestoreTask(
                    globalLock,
                    arguments
            ).call();
            
            logger.info("Restore completed successfully.");
            
            System.exit(0);
            
        } catch (final Exception e) {
            logger.error("Failed to complete restore.", e);
            
            System.exit(1);
        }
        
        return null;
    }
}