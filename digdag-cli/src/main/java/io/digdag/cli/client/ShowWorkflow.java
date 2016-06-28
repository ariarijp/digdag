package io.digdag.cli.client;

import io.digdag.cli.CollectionPrinter;
import io.digdag.cli.SystemExitException;
import io.digdag.client.DigdagClient;
import io.digdag.client.api.RestProject;
import io.digdag.client.api.RestWorkflowDefinition;
import io.digdag.core.Version;

import javax.ws.rs.NotFoundException;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static io.digdag.cli.SystemExitException.systemExit;

public class ShowWorkflow
    extends ClientCommand
{
    public ShowWorkflow(Version version, PrintStream out, PrintStream err)
    {
        super(version, out, err);
    }

    @Override
    public void mainWithClientException()
        throws Exception
    {
        switch (args.size()) {
        case 0:
            showWorkflows(null);
            break;
        case 1:
            showWorkflows(args.get(0));
            break;
        case 2:
            showWorkflowDetails(args.get(0), args.get(1));
            break;
        default:
            throw usage(null);
        }
    }

    public SystemExitException usage(String error)
    {
        err.println("Usage: digdag workflows [project-name] [name]");
        showCommonOptions();
        return systemExit(error);
    }

    private void showWorkflows(String projName)
        throws Exception
    {
        DigdagClient client = buildClient();

        CollectionPrinter<RestWorkflowDefinition> formatter = new CollectionPrinter<>();
        formatter.column("PROJECT", wf -> wf.getProject().getName());
        formatter.column("PROJECT ID", wf -> Integer.toString(wf.getProject().getId()));
        formatter.column("WORKFLOW", wf -> wf.getName());
        formatter.column("REVISION", wf -> wf.getRevision());

        List<RestWorkflowDefinition> defs;
        if (projName != null) {
            RestProject proj = client.getProject(projName);
            defs = client.getWorkflowDefinitions(proj.getId());
        }
        else {
            defs = new ArrayList<>();
            for (RestProject proj : client.getProjects()) {
                try {
                    defs.addAll(client.getWorkflowDefinitions(proj.getId()));
                }
                catch (NotFoundException ex) {
                    continue;
                }
            }
        }
        formatter.print(format, defs, out);
        out.println();
        out.flush();
        err.println("Use `digdag workflows <project-name> <name>` to show details.");
    }

    private void showWorkflowDetails(String projName, String defName)
        throws Exception
    {
        DigdagClient client = buildClient();

        if (projName != null) {
            RestProject proj = client.getProject(projName);
            RestWorkflowDefinition def = client.getWorkflowDefinition(proj.getId(), defName);
            String yaml = yamlMapper().toYaml(def.getConfig());
            ln("%s", yaml);
        }
        else {
            for (RestProject proj : client.getProjects()) {
                try {
                    RestWorkflowDefinition def = client.getWorkflowDefinition(proj.getId(), defName);
                    String yaml = yamlMapper().toYaml(def.getConfig());
                    ln("%s", yaml);
                    return;
                }
                catch (NotFoundException ex) {
                }
            }
            throw systemExit("Workflow definition '" + defName + "' does not exist.");
        }
    }
}
