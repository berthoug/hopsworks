/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.kth.bbc.security.ua;

import se.kth.hopsworks.message.controller.MessageController;
import se.kth.hopsworks.user.model.Users;
import se.kth.hopsworks.users.UserFacade;
import javax.ejb.EJB;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.RequestScoped;
import java.io.Serializable;


@ManagedBean(name="maintenance")
@RequestScoped
public class MaintenanceBean implements Serializable {

    @EJB
    private MaintenanceFacade maintenanceFacade;

    @EJB
    private UserFacade userFacade;

    @EJB
    private MessageController messageController;

    public MaintenanceBean() {
    }

    public Maintenance getMaintenance() {
        return maintenanceFacade.findMaintenanceStatus();
    }

    public short getStatus() {
        return getMaintenance().getStatus();
    }

    public void setStatus(short status) {
        maintenanceFacade.updateStatus(status);
    }

    public String getMessage() {
        return getMaintenance().getMessage();
    }

    public void setMessage(String message) {
        maintenanceFacade.updateBannerMessage(message);
    }

    public void update(short status, String message) {
        setStatus(status);
        setMessage(message);

        if (status == 1) {
            messageController.sendToMany(userFacade.findAllUsers(), userFacade.findByEmail("admin@kth.se"), "Administration Message", message, "");
        }
    }

}