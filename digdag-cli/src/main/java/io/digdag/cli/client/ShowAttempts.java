package io.digdag.cli.client;

import com.beust.jcommander.Parameter;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import io.digdag.cli.CollectionPrinter;
import io.digdag.cli.SystemExitException;
import io.digdag.cli.TimeUtil;
import io.digdag.client.DigdagClient;
import io.digdag.client.api.RestSessionAttempt;
import io.digdag.core.Version;

import java.io.PrintStream;
import java.util.List;

import static io.digdag.cli.SystemExitException.systemExit;

public class ShowAttempts
        extends ClientCommand
{
    @Parameter(names = {"-i", "--last-id"})
    Long lastId = null;

    public ShowAttempts(Version version, PrintStream out, PrintStream err)
    {
        super(version, out, err);
    }

    @Override
    public void mainWithClientException()
            throws Exception
    {
        switch (args.size()) {
            case 0:
                showAttempts(null);
                break;
            case 1:
                try {
                    long sessionId = Long.parseUnsignedLong(args.get(0));
                    showAttempts(sessionId);
                }
                catch (NumberFormatException ignore) {
                    throw usage("Invalid session id: " + args.get(0));
                }
                break;
            default:
                throw usage(null);
        }
    }

    public SystemExitException usage(String error)
    {
        err.println("Usage: digdag attempts                         show attempts for all sessions");
        err.println("       digdag attempts <session-id>            show attempts for a session");
        err.println("  Options:");
        err.println("    -i, --last-id ID                 shows more session attempts from this id");
        showCommonOptions();
        return systemExit(error);
    }

    private void showAttempts(Long sessionId)
            throws Exception
    {
        DigdagClient client = buildClient();
        List<RestSessionAttempt> attempts;

        CollectionPrinter<RestSessionAttempt> printer = new CollectionPrinter<>();

        printer.column("SESSION ID", s -> Long.toString(s.getId()));
        printer.column("ATTEMPT ID", sa -> Integer.toString(sa.getProject().getId()));
        printer.column("PROJECT", s -> s.getProject().getName());
        printer.column("WORKFLOW", s -> s.getWorkflow().getName());
        printer.column("SESSION TIME", s -> TimeUtil.formatTime(s.getSessionTime()));
        printer.column("CREATED", a -> TimeUtil.formatTime(a.getCreatedAt()));
        printer.column("KILLED", a -> Boolean.toString(a.getCancelRequested()));
        printer.column("STATUS", s -> status(s).toUpperCase());
        printer.column("RETRY NAME", sa -> sa.getRetryAttemptName().or(""));

        if (sessionId == null) {
            attempts = client.getSessionAttempts(Optional.fromNullable(lastId));
        }
        else {
            attempts = client.getSessionAttempts(sessionId, Optional.fromNullable(lastId));
        }

        printer.print(format, Lists.reverse(attempts), out);

        out.println();
        out.flush();

        if (attempts.isEmpty()) {
            err.println("Use `digdag start` to start a session.");
        }
    }

    private String status(RestSessionAttempt attempt)
    {
        String status;
        if (attempt.getSuccess()) {
            status = "success";
        }
        else if (attempt.getDone()) {
            status = "error";
        }
        else {
            status = "running";
        }
        return status;
    }
}
