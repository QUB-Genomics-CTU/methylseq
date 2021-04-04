/*
 * This file holds several functions used to perform JSON parameter validation, help and summary rendering for the nf-core pipeline template.
 */

import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.everit.json.schema.ValidationException
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONArray
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder

class NfcoreSchema {

    /*
    * Function to loop over all parameters defined in schema and check
    * whether the given paremeters adhere to the specificiations
    */
    /* groovylint-disable-next-line UnusedPrivateMethodParameter */
    private static void validateParameters(params, jsonSchema, log) {
        def has_error = false
        //=====================================================================//
        // Check for nextflow core params and unexpected params
        def json = new File(jsonSchema).text
        def Map schemaParams = (Map) new JsonSlurper().parseText(json).get('definitions')
        def nf_params = [
            // Options for base `nextflow` command
            'bg',
            'c',
            'C',
            'config',
            'd',
            'D',
            'dockerize',
            'h',
            'log',
            'q',
            'quiet',
            'syslog',
            'v',
            'version',

            // Options for `nextflow run` command
            'ansi',
            'ansi-log',
            'bg',
            'bucket-dir',
            'c',
            'cache',
            'config',
            'dsl2',
            'dump-channels',
            'dump-hashes',
            'E',
            'entry',
            'latest',
            'lib',
            'main-script',
            'N',
            'name',
            'offline',
            'params-file',
            'pi',
            'plugins',
            'poll-interval',
            'pool-size',
            'profile',
            'ps',
            'qs',
            'queue-size',
            'r',
            'resume',
            'revision',
            'stdin',
            'stub',
            'stub-run',
            'test',
            'w',
            'with-charliecloud',
            'with-conda',
            'with-dag',
            'with-docker',
            'with-mpi',
            'with-notification',
            'with-podman',
            'with-report',
            'with-singularity',
            'with-timeline',
            'with-tower',
            'with-trace',
            'with-weblog',
            'without-docker',
            'without-podman',
            'work-dir'
        ]
        def unexpectedParams = []

        // Collect expected parameters from the schema
        def expectedParams = []
        for (group in schemaParams) {
            for (p in group.value['properties']) {
                expectedParams.push(p.key)
            }
        }

        for (specifiedParam in params.keySet()) {
            // nextflow params
            if (nf_params.contains(specifiedParam)) {
                log.error "ERROR: You used a core Nextflow option with two hyphens: '--${specifiedParam}'. Please resubmit with '-${specifiedParam}'"
                has_error = true
            }
            // unexpected params
            def params_ignore = params.schema_ignore_params.split(',') + 'schema_ignore_params'
            if (!expectedParams.contains(specifiedParam) && !params_ignore.contains(specifiedParam)) {
                unexpectedParams.push(specifiedParam)
            }
        }

        //=====================================================================//
        // Validate parameters against the schema
        InputStream inputStream = new File(jsonSchema).newInputStream()
        JSONObject rawSchema = new JSONObject(new JSONTokener(inputStream))

        // Remove anything that's in params.schema_ignore_params
        rawSchema = removeIgnoredParams(rawSchema, params)

        Schema schema = SchemaLoader.load(rawSchema)

        // Clean the parameters
        def cleanedParams = cleanParameters(params)

        // Convert to JSONObject
        def jsonParams = new JsonBuilder(cleanedParams)
        JSONObject paramsJSON = new JSONObject(jsonParams.toString())

        // Validate
        try {
            schema.validate(paramsJSON)
        } catch (ValidationException e) {
            println ''
            log.error 'ERROR: Validation of pipeline parameters failed!'
            JSONObject exceptionJSON = e.toJSON()
            printExceptions(exceptionJSON, paramsJSON, log)
            println ''
            has_error = true
        }

        // Check for unexpected parameters
        if (unexpectedParams.size() > 0) {
            Map colors = Utils.logColours(params.monochrome_logs)
            println ''
            def warn_msg = 'Found unexpected parameters:'
            for (unexpectedParam in unexpectedParams) {
                warn_msg = warn_msg + "\n* --${unexpectedParam}: ${params[unexpectedParam].toString()}"
            }
            log.warn warn_msg
            log.info "- ${colors.dim}Ignore this warning: params.schema_ignore_params = \"${unexpectedParams.join(',')}\" ${colors.reset}"
            println ''
        }

        if (has_error) {
            System.exit(1)
        }
    }

    // Loop over nested exceptions and print the causingException
    private static void printExceptions(exJSON, paramsJSON, log) {
        def causingExceptions = exJSON['causingExceptions']
        if (causingExceptions.length() == 0) {
            def m = exJSON['message'] =~ /required key \[([^\]]+)\] not found/
            // Missing required param
            if (m.matches()) {
                log.error "* Missing required parameter: --${m[0][1]}"
            }
            // Other base-level error
            else if (exJSON['pointerToViolation'] == '#') {
                log.error "* ${exJSON['message']}"
            }
            // Error with specific param
            else {
                def param = exJSON['pointerToViolation'] - ~/^#\//
                def param_val = paramsJSON[param].toString()
                log.error "* --${param}: ${exJSON['message']} (${param_val})"
            }
        }
        for (ex in causingExceptions) {
            printExceptions(ex, paramsJSON, log)
        }
    }

    // Remove an element from a JSONArray
    private static JSONArray removeElement(jsonArray, element){
        def list = []
        int len = jsonArray.length()
        for (int i=0;i<len;i++){
            list.add(jsonArray.get(i).toString())
        }
        list.remove(element)
        JSONArray jsArray = new JSONArray(list)
        return jsArray
    }

    private static JSONObject removeIgnoredParams(rawSchema, params){
        // Remove anything that's in params.schema_ignore_params
        params.schema_ignore_params.split(',').each{ ignore_param ->
            if(rawSchema.keySet().contains('definitions')){
                rawSchema.definitions.each { definition ->
                    for (key in definition.keySet()){
                        if (definition[key].get("properties").keySet().contains(ignore_param)){
                            // Remove the param to ignore
                            definition[key].get("properties").remove(ignore_param)
                            // If the param was required, change this
                            if (definition[key].has("required")) {
                                def cleaned_required = removeElement(definition[key].required, ignore_param)
                                definition[key].put("required", cleaned_required)
                            }
                        }
                    }
                }
            }
            if(rawSchema.keySet().contains('properties') && rawSchema.get('properties').keySet().contains(ignore_param)) {
                rawSchema.get("properties").remove(ignore_param)
            }
            if(rawSchema.keySet().contains('required') && rawSchema.required.contains(ignore_param)) {
                def cleaned_required = removeElement(rawSchema.required, ignore_param)
                rawSchema.put("required", cleaned_required)
            }
        }
        return rawSchema
    }

    private static Map cleanParameters(params) {
        def new_params = params.getClass().newInstance(params)
        for (p in params) {
            // remove anything evaluating to false
            if (!p['value']) {
                new_params.remove(p.key)
            }
            // Cast MemoryUnit to String
            if (p['value'].getClass() == nextflow.util.MemoryUnit) {
                new_params.replace(p.key, p['value'].toString())
            }
            // Cast Duration to String
            if (p['value'].getClass() == nextflow.util.Duration) {
                new_params.replace(p.key, p['value'].toString())
            }
            // Cast LinkedHashMap to String
            if (p['value'].getClass() == LinkedHashMap) {
                new_params.replace(p.key, p['value'].toString())
            }
        }
        return new_params
    }

     /*
     * This method tries to read a JSON params file
     */
    private static LinkedHashMap paramsLoad(String json_schema) {
        def params_map = new LinkedHashMap()
        try {
            params_map = paramsRead(json_schema)
        } catch (Exception e) {
            println "Could not read parameters settings from JSON. $e"
            params_map = new LinkedHashMap()
        }
        return params_map
    }

    /*
    Method to actually read in JSON file using Groovy.
    Group (as Key), values are all parameters
        - Parameter1 as Key, Description as Value
        - Parameter2 as Key, Description as Value
        ....
    Group
        -
    */
    private static LinkedHashMap paramsRead(String json_schema) throws Exception {
        def json = new File(json_schema).text
        def Map schema_definitions = (Map) new JsonSlurper().parseText(json).get('definitions')
        def Map schema_properties = (Map) new JsonSlurper().parseText(json).get('properties')
        /* Tree looks like this in nf-core schema
         * definitions <- this is what the first get('definitions') gets us
             group 1
               title
               description
                 properties
                   parameter 1
                     type
                     description
                   parameter 2
                     type
                     description
             group 2
               title
               description
                 properties
                   parameter 1
                     type
                     description
         * properties <- parameters can also be ungrouped, outside of definitions
            parameter 1
             type
             description
        */

        // Grouped params
        def params_map = new LinkedHashMap()
        schema_definitions.each { key, val ->
            def Map group = schema_definitions."$key".properties // Gets the property object of the group
            def title = schema_definitions."$key".title
            def sub_params = new LinkedHashMap()
            group.each { innerkey, value ->
                sub_params.put(innerkey, value)
            }
            params_map.put(title, sub_params)
        }

        // Ungrouped params
        def ungrouped_params = new LinkedHashMap()
        schema_properties.each { innerkey, value ->
            ungrouped_params.put(innerkey, value)
        }
        params_map.put("Other parameters", ungrouped_params)

        return params_map
    }

    /*
     * Get maximum number of characters across all parameter names
     */
    private static Integer paramsMaxChars(params_map) {
        Integer max_chars = 0
        for (group in params_map.keySet()) {
            def group_params = params_map.get(group)  // This gets the parameters of that particular group
            for (param in group_params.keySet()) {
                if (param.size() > max_chars) {
                    max_chars = param.size()
                }
            }
        }
        return max_chars
    }

    /*
     * Beautify parameters for --help
     */
    private static String paramsHelp(workflow, params, json_schema, command) {
        Map colors = Utils.logColours(params.monochrome_logs)
        Integer num_hidden = 0
        String output  = ''
        output        += 'Typical pipeline command:\n\n'
        output        += "  ${colors.cyan}${command}${colors.reset}\n\n"
        Map params_map = paramsLoad(json_schema)
        Integer max_chars  = paramsMaxChars(params_map) + 1
        Integer desc_indent = max_chars + 14
        Integer dec_linewidth = 160 - desc_indent
        for (group in params_map.keySet()) {
            Integer num_params = 0
            String group_output = colors.underlined + colors.bold + group + colors.reset + '\n'
            def group_params = params_map.get(group)  // This gets the parameters of that particular group
            for (param in group_params.keySet()) {
                if (group_params.get(param).hidden && !params.show_hidden_params) {
                    num_hidden += 1
                    continue;
                }
                def type = '[' + group_params.get(param).type + ']'
                def description = group_params.get(param).description
                def defaultValue = group_params.get(param).default ? " [default: " + group_params.get(param).default.toString() + "]" : ''
                def description_default = description + colors.dim + defaultValue + colors.reset
                // Wrap long description texts
                // Loosely based on https://dzone.com/articles/groovy-plain-text-word-wrap
                if (description_default.length() > dec_linewidth){
                    List olines = []
                    String oline = "" // " " * indent
                    description_default.split(" ").each() { wrd ->
                        if ((oline.size() + wrd.size()) <= dec_linewidth) {
                            oline += wrd + " "
                        } else {
                            olines += oline
                            oline = wrd + " "
                        }
                    }
                    olines += oline
                    description_default = olines.join("\n" + " " * desc_indent)
                }
                group_output += "  --" +  param.padRight(max_chars) + colors.dim + type.padRight(10) + colors.reset + description_default + '\n'
                num_params += 1
            }
            group_output += '\n'
            if (num_params > 0){
                output += group_output
            }
        }
        if (num_hidden > 0){
            output += colors.dim + "!! Hiding $num_hidden params, use --show_hidden_params to show them !!\n" + colors.reset
        }
        output += Utils.dashedLine(params.monochrome_logs)
        return output
    }

    /*
     * Groovy Map summarising parameters/workflow options used by the pipeline
     */
    private static LinkedHashMap paramsSummaryMap(workflow, params, json_schema) {
        // Get a selection of core Nextflow workflow options
        def Map workflow_summary = [:]
        if (workflow.revision) {
            workflow_summary['revision'] = workflow.revision
        }
        workflow_summary['runName']      = workflow.runName
        if (workflow.containerEngine) {
            workflow_summary['containerEngine'] = workflow.containerEngine
        }
        if (workflow.container) {
            workflow_summary['container'] = workflow.container
        }
        workflow_summary['launchDir']    = workflow.launchDir
        workflow_summary['workDir']      = workflow.workDir
        workflow_summary['projectDir']   = workflow.projectDir
        workflow_summary['userName']     = workflow.userName
        workflow_summary['profile']      = workflow.profile
        workflow_summary['configFiles']  = workflow.configFiles.join(', ')

        // Get pipeline parameters defined in JSON Schema
        def Map params_summary = [:]
        def blacklist  = ['hostnames']
        def params_map = paramsLoad(json_schema)
        for (group in params_map.keySet()) {
            def sub_params = new LinkedHashMap()
            def group_params = params_map.get(group)  // This gets the parameters of that particular group
            for (param in group_params.keySet()) {
                if (params.containsKey(param) && !blacklist.contains(param)) {
                    def params_value = params.get(param)
                    def schema_value = group_params.get(param).default
                    def param_type   = group_params.get(param).type
                    if (schema_value != null) {
                        if (param_type == 'string') {
                            if (schema_value.contains('$projectDir') || schema_value.contains('${projectDir}')) {
                                def sub_string = schema_value.replace('\$projectDir', '')
                                sub_string     = sub_string.replace('\${projectDir}', '')
                                if (params_value.contains(sub_string)) {
                                    schema_value = params_value
                                }
                            }
                            if (schema_value.contains('$params.outdir') || schema_value.contains('${params.outdir}')) {
                                def sub_string = schema_value.replace('\$params.outdir', '')
                                sub_string     = sub_string.replace('\${params.outdir}', '')
                                if ("${params.outdir}${sub_string}" == params_value) {
                                    schema_value = params_value
                                }
                            }
                        }
                    }

                    // We have a default in the schema, and this isn't it
                    if (schema_value != null && params_value != schema_value) {
                        sub_params.put(param, params_value)
                    }
                    // No default in the schema, and this isn't empty
                    else if (schema_value == null && params_value != "" && params_value != null && params_value != false) {
                        sub_params.put(param, params_value)
                    }
                }
            }
            params_summary.put(group, sub_params)
        }
        return [ 'Core Nextflow options' : workflow_summary ] << params_summary
    }

    /*
     * Beautify parameters for summary and return as string
     */
    private static String paramsSummaryLog(workflow, params, json_schema) {
        Map colors = Utils.logColours(params.monochrome_logs)
        String output  = ''
        def params_map = paramsSummaryMap(workflow, params, json_schema)
        def max_chars  = paramsMaxChars(params_map)
        for (group in params_map.keySet()) {
            def group_params = params_map.get(group)  // This gets the parameters of that particular group
            if (group_params) {
                output += colors.bold + group + colors.reset + '\n'
                for (param in group_params.keySet()) {
                    output += "  " + colors.blue + param.padRight(max_chars) + ": " + colors.green +  group_params.get(param) + colors.reset + '\n'
                }
                output += '\n'
            }
        }
        output += Utils.dashedLine(params.monochrome_logs)
        output += colors.dim + "\n Only displaying parameters that differ from defaults.\n" + colors.reset
        output += Utils.dashedLine(params.monochrome_logs)
        return output
    }
}
