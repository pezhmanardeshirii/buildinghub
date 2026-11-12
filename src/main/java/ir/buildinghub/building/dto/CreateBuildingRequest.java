package ir.buildinghub.building.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBuildingRequest(

        @NotBlank(message = "نام ساختمان اجباریه")
        @Size(max = 150, message = "نام ساختمان از 150 کاراکتر بیشتر نباشد")
        String name,
        @NotBlank(message = "آدرس ساختمان اجباریه")
        @Size(max = 300, message = "آدرس ساختمان از 300 کاراکتر بیشتر نباشد")
        String address) {
}
