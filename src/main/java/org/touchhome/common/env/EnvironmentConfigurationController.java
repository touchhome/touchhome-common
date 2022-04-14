package org.touchhome.common.env;

import io.swagger.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.touchhome.common.env.etcd.EtcdStat;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Api(value = "Environment Configuration", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
@RequestMapping(value = "environment", produces = MediaType.APPLICATION_JSON_VALUE)
public class EnvironmentConfigurationController
{
    @Autowired
    private EnvironmentPropertyHolder env;

    @ApiOperation(value = "Retrieve Status",
            notes = "Returns a status for the id provided.",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponses({ @ApiResponse(code = 200, message = "Returns the status for the id provided."),
            @ApiResponse(code = 500, message = "Internal server error."),
            @ApiResponse(code = 404, message = "Status not found.") })
    @GetMapping
    public Collection<EnvironmentPropertyModel> retrieveProcessStatus()
    {
        return env.getProperties().values();
    }


    @ApiOperation(value = "Returns property or environment variable value")
    @ApiResponses({ @ApiResponse(code = 200, message = "Value") })
    @GetMapping(value = "/{name:.+}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> getEnvProperty(
            @ApiParam(name = "name", value = "property name") @PathVariable String name)
    {
        Map<String, String> response = new HashMap<>();
        response.put(name, env.getProperty(name));
        return response;
    }

    @ApiOperation(value = "Set environment variable value if possible")
    @ApiResponses({ @ApiResponse(code = 200, message = "Value") })
    @PutMapping(value = "/{name:.+}")
    @ResponseStatus(HttpStatus.OK)
    public void setEnvProperty(
            @ApiParam(name = "name", value = "property name")
            @PathVariable String name,

            @ApiParam(name = "value", value = "property value")
            @RequestBody String value) throws ExecutionException, InterruptedException
    {
        env.updateProperty(name, value);
    }

    @ApiOperation(value = "Get environment variable names that able to update. Key: property name, Value: property "
            + "type")
    @GetMapping(value = "/updatable")
    @ResponseStatus(HttpStatus.OK)
    public Collection<EnvironmentPropertyModel> getUpdatableEnvProperties()
    {
        return env.getUpdatableProperties();
    }

    @ApiOperation(value = "Remove environment variable from etcd")
    @DeleteMapping(value = "etcd/{name:.+}")
    @ResponseStatus(HttpStatus.OK)
    public void deleteEtcdProperties(@ApiParam(name = "name",
            value = "property name") @PathVariable String name,
                                     @RequestParam(value = "prefix", defaultValue = "false") boolean prefix)
            throws ExecutionException, InterruptedException
    {
        env.removeProperty(name, prefix);
    }

    @ApiOperation(value = "Get etcd stats")
    @GetMapping(value = "/etcd")
    @ResponseStatus(HttpStatus.OK)
    public EtcdStat getEtcdStat()
    {
        return env.getEtcdStat();
    }
}
