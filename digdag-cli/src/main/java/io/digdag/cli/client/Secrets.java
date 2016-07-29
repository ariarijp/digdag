package io.digdag.cli.client;

import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import io.digdag.cli.SystemExitException;
import io.digdag.client.DigdagClient;
import io.digdag.client.api.RestProject;
import io.digdag.client.api.RestSecretList;
import io.digdag.core.Version;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sun.mail.imap.protocol.SearchSequence.isAscii;
import static io.digdag.cli.SystemExitException.systemExit;
import static io.digdag.client.DigdagClient.objectMapper;
import static java.lang.Boolean.TRUE;

public class Secrets
        extends ClientCommand
{
    @Parameter(names = {"--set"}, variableArity = true)
    List<String> set = new ArrayList<>();

    @Parameter(names = {"--delete"}, variableArity = true)
    List<String> delete = new ArrayList<>();

    private final InputStream in;

    public Secrets(Version version, PrintStream out, PrintStream err, InputStream in)
    {
        super(version, out, err);
        this.in = in;
    }

    @Override
    public void mainWithClientException()
            throws Exception
    {
        if (args.size() != 1) {
            throw usage(null);
        }

        String projectName = args.get(0);

        DigdagClient client = buildClient();
        RestProject project = client.getProject(projectName);

        if (!set.isEmpty() && !delete.isEmpty()) {
            throw usage("Please specify only one of --set or --delete");
        }

        if (set.isEmpty() && delete.isEmpty()) {
            listProjectSecrets(client, project.getId());
        }
        else if (!set.isEmpty()) {
            setProjectSecrets(client, project.getId(), set);
        }
        else if (!delete.isEmpty()) {
            deleteProjectSecrets(client, project.getId(), delete);
        }
        else {
            throw new AssertionError();
        }
    }

    private void deleteProjectSecrets(DigdagClient client, int projectId, List<String> delete)
            throws SystemExitException
    {
        Map<String, Boolean> deleteSecrets = new LinkedHashMap<>();

        boolean inConsumed = false;

        for (String key : delete) {

            if (key.isEmpty()) {
                throw usage(null);
            }

            if (deleteSecrets.containsKey(key)) {
                throw usage("Duplicate --delete secret key: '" + key + "'");
            }

            deleteSecrets.put(key, TRUE);
        }

        for (String key : deleteSecrets.keySet()) {
            if (!isAscii(key)) {
                throw usage("Invalid secret key: " + key);
            }
        }
        // Delete secrets
        for (String key : deleteSecrets.keySet()) {
            client.deleteProjectSecret(projectId, key);
        }
    }

    private void setProjectSecrets(DigdagClient client, int projectId, List<String> set)
            throws Exception
    {
        Map<String, String> setSecrets = new LinkedHashMap<>();

        boolean inConsumed = false;

        for (String s : set) {
            if (s.isEmpty()) {
                throw usage(null);
            }

            // Read a list of secrets from stdin or a file?
            if (s.charAt(0) == '@') {
                String filename = s.substring(1);
                if (filename.isEmpty()) {
                    throw usage(null);
                }
                Map<String, String> secrets;
                String source;
                if (filename.charAt(0) == '-') {
                    // Read stdin as yaml (or json)
                    if (filename.length() != 1) {
                        throw usage(null);
                    }
                    if (inConsumed) {
                        throw usage("Can only read once from stdin");
                    }
                    inConsumed = true;
                    source = "stdin";

                    YAMLFactory yaml = new YAMLFactory();
                    YAMLParser parser = yaml.createParser(in);
                    secrets = DigdagClient.objectMapper().readValue(parser, new TypeReference<Map<String, String>>() {});
                }
                else {
                    // Read secrets from file
                    source = "file: " + filename;
                    Map<String, String> result;
                    YAMLFactory yaml = new YAMLFactory();
                    try (YAMLParser parser = yaml.createParser(Files.newInputStream(Paths.get(filename)))) {
                        result = objectMapper().readValue(parser, new TypeReference<Map<String, String>>() {});
                    }
                    secrets = result;
                }
                for (Map.Entry<String, String> entry : secrets.entrySet()) {
                    if (entry.getKey().isEmpty() || entry.getValue().isEmpty()) {
                        throw usage("Invalid key/value in " + source);
                    }
                    if (setSecrets.containsKey(entry.getKey())) {
                        throw usage("Duplicate secret key: '" + entry.getKey() + "' in " + source);
                    }
                    setSecrets.put(entry.getKey(), entry.getValue());
                }
                continue;
            }

            // A key=value
            List<String> tuple = Splitter.on('=').splitToList(s);
            if (tuple.size() != 2) {
                throw usage(null);
            }
            String key = tuple.get(0);
            String value = tuple.get(1);
            if (key.isEmpty() || value.isEmpty()) {
                throw usage(null);
            }
            if (setSecrets.containsKey(key)) {
                throw usage("Duplicate --set secret key: '" + key + "'");
            }

            // Read value from file?
            if (value.charAt(0) == '@') {
                String filename = value.substring(1);
                if (filename.isEmpty()) {
                    throw usage(null);
                }
                Path path = Paths.get(filename);
                List<String> lines = Files.readAllLines(path);
                if (lines.isEmpty()) {
                    throw usage("Failed to read secret from file: " + filename);
                }
                value = lines.get(0);
                setSecrets.put(key, value);
                continue;
            }

            // Read value from stdin?
            if (value.charAt(0) == '-') {
                if (value.length() != 1) {
                    throw usage(null);
                }
                if (inConsumed) {
                    throw usage("Can only read once from stdin");
                }
                inConsumed = true;

                String input = CharStreams.toString(new InputStreamReader(in));
                if (input.isEmpty()) {
                    throw usage("Empty input for key: " + key);
                }

                setSecrets.put(key, input);
                continue;
            }

            // Just a regular key=value
            setSecrets.put(key, value);
        }

        // Validate keys and values
        for (Map.Entry<String, String> entry : setSecrets.entrySet()) {
            if (!isAscii(entry.getKey())) {
                throw usage("Invalid secret key: " + entry.getKey());
            }
            if (!isAscii(entry.getValue())) {
                throw usage("Invalid secret value for key: " + entry.getKey());
            }
        }

        // Set secrets
        for (Map.Entry<String, String> entry : setSecrets.entrySet()) {
            client.setProjectSecret(projectId, entry.getKey(), entry.getValue());
        }
    }

    private void listProjectSecrets(DigdagClient client, int projectId)
    {
        RestSecretList secretList = client.listProjectSecrets(projectId);
        secretList.secrets().forEach(s -> out.println(s.key()));
    }

    public SystemExitException usage(String error)
    {
        err.println("Usage: digdag secrets <project> [--set <key>=<value>] [--delete key]");
        showCommonOptions();
        return systemExit(error);
    }
}
