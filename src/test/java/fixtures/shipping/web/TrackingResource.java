package fixtures.shipping.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/tracking")
public class TrackingResource {

    @GET
    @Path("{id}")
    public String track() {
        return "ok";
    }
}
